package icu.sakuracianna.mianba.interview.material;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * PostgreSQL 材料服务。
 *
 * 上传原始文件只在内存中短暂保留并交给独立解析进程，API JVM 不加载 PDF 或 ZIP 解析路径；
 * 数据库只保存最多 12,000 字的纯文本和摘要，原始文件不会落盘。
 */
@Service
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class JdbcMaterialService implements MaterialService {
    private static final int MAX_UPLOAD_BYTES = MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES;
    private static final int MAX_TEXT_CHARS = MaterialUploadPolicy.DEFAULT_MAX_TEXT_CHARS;
    private static final int MAX_MATERIALS_PER_DAY = 10;
    private static final int MAX_MATERIALS_PER_30_DAYS = 30;
    private static final int MAX_ACTIVE_MATERIALS = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AbuseProtection abuseProtection;
    private final TransactionTemplate transactions;
    private final MaterialParserClient parserClient;
    private final Semaphore uploadCapacity = new Semaphore(1, true);

    public JdbcMaterialService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AbuseProtection abuseProtection,
            TransactionTemplate transactions,
            MaterialParserClient parserClient) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.abuseProtection = abuseProtection;
        this.transactions = transactions;
        this.parserClient = parserClient;
    }

    @Override
    public MaterialView upload(
            UUID userId,
            String idempotencyKey,
            String interviewType,
            MultipartFile resumeFile,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major,
            String researchDirection) {
        if (!uploadCapacity.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "material_upload_capacity_full", "材料上传繁忙，请稍后重试");
        }
        try {
            return doUpload(userId, idempotencyKey, interviewType, resumeFile,
                    jobTitle, jobRequirements, targetSchool, major, researchDirection);
        } finally {
            uploadCapacity.release();
        }
    }

    private MaterialView doUpload(
            UUID userId,
            String idempotencyKey,
            String interviewType,
            MultipartFile resumeFile,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major,
            String researchDirection) {
        String type = normalizeType(interviewType);
        validateBusinessFields(type, resumeFile, jobTitle, jobRequirements, targetSchool, major);
        String filename = resumeFile == null ? null : nullable(MaterialFilename.sanitize(resumeFile.getOriginalFilename()));
        String rawContentType = resumeFile == null ? null : nullable(resumeFile.getContentType());
        String contentType = rawContentType == null ? null : rawContentType.toLowerCase(Locale.ROOT);
        String normalizedJobTitle = nullable(jobTitle);
        String normalizedJobRequirements = nullable(jobRequirements);
        String normalizedTargetSchool = nullable(targetSchool);
        String normalizedMajor = nullable(major);
        String normalizedResearchDirection = nullable(researchDirection);

        ExistingMaterial existing = findExistingMaterial(userId, idempotencyKey);
        if (existing == null) {
            abuseProtection.check("material-create-daily", userId.toString(),
                    MAX_MATERIALS_PER_DAY, Duration.ofDays(1));
            abuseProtection.check("material-create-monthly", userId.toString(),
                    MAX_MATERIALS_PER_30_DAYS, Duration.ofDays(30));
        }

        byte[] bytes = readBytes(resumeFile);
        String sourceHash = sha256(bytes);
        String requestHash = requestHash(
                sourceHash, type, filename, contentType, normalizedJobTitle, normalizedJobRequirements,
                normalizedTargetSchool, normalizedMajor, normalizedResearchDirection);
        if (existing != null) {
            return replayExistingMaterial(userId, existing, requestHash);
        }

        ParsedMaterial parsed = parse(resumeFile, bytes);
        String parsedText = normalizeText(parsed.text());
        if (resumeFile != null && !resumeFile.isEmpty() && parsedText.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "resume_text_empty",
                    "文件中没有提取到可用文字");
        }
        String normalizedText = parsedText.length() <= MAX_TEXT_CHARS
                ? parsedText : parsedText.substring(0, MAX_TEXT_CHARS);
        String profileSummary = summary(
                normalizedText, type, normalizedJobTitle, normalizedTargetSchool, normalizedMajor);
        List<String> keywords = keywords(
                normalizedText, normalizedJobTitle, normalizedMajor, normalizedResearchDirection);

        MaterialView result = transactions.execute(transaction -> {
            // 仅在解析完成后开启短事务；锁用户行可串行化同一用户的新建请求，防止并发越过数量上限。
            List<UUID> lockedUsers = jdbc.query("""
                    SELECT id FROM users WHERE id = ? FOR UPDATE
                    """, (rs, row) -> rs.getObject("id", UUID.class), userId);
            if (lockedUsers.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在");
            }
            ExistingMaterial concurrent = findExistingMaterial(userId, idempotencyKey);
            if (concurrent != null) {
                return replayExistingMaterial(userId, concurrent, requestHash);
            }
            requireActiveMaterialCapacity(userId);

            UUID candidateId = UUID.randomUUID();
            List<UUID> inserted = jdbc.query("""
                    INSERT INTO materials(
                        id, user_id, upload_idempotency_key, source_sha256, request_hash, interview_type, status,
                        resume_filename, resume_content_type, resume_size_bytes, resume_text,
                        job_title, job_requirements, target_school, major, research_direction,
                        profile_summary, keywords, retention_until)
                    VALUES (?, ?, ?, ?, ?, ?, 'ready', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                            now() + interval '30 days')
                    ON CONFLICT (user_id, upload_idempotency_key) DO NOTHING
                    RETURNING id
                    """, (rs, row) -> rs.getObject("id", UUID.class), candidateId, userId, idempotencyKey,
                    sourceHash, requestHash, type, filename, contentType, bytes.length,
                    nullable(normalizedText), normalizedJobTitle, normalizedJobRequirements,
                    normalizedTargetSchool, normalizedMajor, normalizedResearchDirection,
                    profileSummary, jsonArray(keywords));
            if (!inserted.isEmpty()) {
                return find(userId, inserted.getFirst());
            }
            // 唯一索引仍作为跨版本兜底；冲突事务完成后必须按原请求语义回放。
            ExistingMaterial winner = findExistingMaterial(userId, idempotencyKey);
            if (winner == null) {
                throw new IllegalStateException("Conflicting interview material was not found");
            }
            return replayExistingMaterial(userId, winner, requestHash);
        });
        return Objects.requireNonNull(result, "Material transaction returned no result");
    }

    void requireActiveMaterialCapacity(UUID userId) {
        Long active = jdbc.queryForObject("""
                SELECT count(*) FROM materials WHERE user_id = ? AND status = 'ready'
                """, Long.class, userId);
        if (active != null && active >= MAX_ACTIVE_MATERIALS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "material_active_limit_reached", "可用面试材料已达上限，请等待旧材料到期后再试");
        }
    }

    private ExistingMaterial findExistingMaterial(UUID userId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, request_hash FROM materials
                WHERE user_id = ? AND upload_idempotency_key = ?
                """, (rs, row) -> new ExistingMaterial(
                rs.getObject("id", UUID.class), rs.getString("request_hash")), userId, idempotencyKey)
                .stream().findFirst().orElse(null);
    }

    private MaterialView replayExistingMaterial(
            UUID userId,
            ExistingMaterial existing,
            String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                    "同一幂等键不能用于不同材料");
        }
        return find(userId, existing.id());
    }

    private MaterialView find(UUID userId, UUID materialId) {
        return jdbc.query("""
                SELECT id, interview_type, job_title, target_school, major, research_direction,
                       resume_text, profile_summary, keywords::text AS keywords_json
                FROM materials WHERE id = ? AND user_id = ? AND status = 'ready'
                """, this::mapView, materialId, userId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "interview_material_not_found", "面试材料不存在"));
    }

    private MaterialView mapView(ResultSet rs, int row) throws SQLException {
        String text = rs.getString("resume_text");
        List<String> parsedKeywords = parseJsonArray(rs.getString("keywords_json"));
        return new MaterialView(
                rs.getObject("id", UUID.class), rs.getString("interview_type"),
                rs.getString("job_title"), rs.getString("target_school"), rs.getString("major"),
                rs.getString("research_direction"), preview(text), text == null ? 0 : text.length(),
                rs.getString("profile_summary"), parsedKeywords);
    }

    private ParsedMaterial parse(MultipartFile file, byte[] bytes) {
        if (file == null || file.isEmpty()) {
            return new ParsedMaterial("", ArchiveInspection.none());
        }
        return parserClient.parse(
                MaterialFilename.sanitize(file.getOriginalFilename()), file.getContentType(), bytes);
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new byte[0];
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "resume_file_too_large", "简历文件不能超过 5 MiB");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "resume_read_failed", "无法读取上传文件");
        }
    }

    private static void validateBusinessFields(
            String type,
            MultipartFile file,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major) {
        if ("job".equals(type)
                && (file == null || file.isEmpty() || blank(jobTitle) || blank(jobRequirements))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "job_material_required_fields", "求职面试需要简历、岗位和岗位要求");
        }
        if ("postgraduate".equals(type) && (blank(targetSchool) || blank(major))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "postgraduate_school_major_required", "考研复试需要目标院校和专业");
        }
    }

    private static String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("job", "postgraduate", "civil_service", "ielts").contains(type)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "unsupported_interview_type", "不支持的面试类型");
        }
        return type;
    }

    private static String summary(String text, String type, String jobTitle, String school, String major) {
        List<String> parts = new ArrayList<>();
        parts.add("训练类型：" + type);
        if (!blank(jobTitle)) {
            parts.add("目标岗位：" + jobTitle.trim());
        }
        if (!blank(school)) {
            parts.add("目标院校：" + school.trim());
        }
        if (!blank(major)) {
            parts.add("专业：" + major.trim());
        }
        if (!text.isBlank()) {
            parts.add("材料摘要：" + preview(text));
        }
        String result = String.join("；", parts);
        return result.length() <= 1200 ? result : result.substring(0, 1200);
    }

    private static List<String> keywords(String text, String... explicit) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : explicit) {
            if (!blank(value)) {
                values.add(value.trim());
            }
        }
        for (String token : text.split("[\\s,，。；;：:、/|]+")) {
            String value = token.trim();
            if (value.length() >= 2 && value.length() <= 24) {
                values.add(value);
            }
            if (values.size() >= 12) {
                break;
            }
        }
        return List.copyOf(values);
    }

    String jsonArray(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize material keywords", exception);
        }
    }

    List<String> parseJsonArray(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String[] values = mapper.readValue(json, String[].class);
            return List.copyOf(java.util.Arrays.asList(values));
        } catch (JacksonException | NullPointerException exception) {
            throw new SQLException("Invalid materials.keywords JSON", exception);
        }
    }

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isISOControl(current)
                    && current != '\n' && current != '\r' && current != '\t') {
                safe.append(' ');
            } else {
                safe.append(current);
            }
        }
        return safe.toString()
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= 320 ? value : value.substring(0, 320) + "...";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requestHash(String... values) {
        String joined = String.join("\u001f", java.util.Arrays.stream(values)
                .map(value -> value == null ? "<null>" : value)
                .toList());
        return sha256(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullable(String value) {
        if (blank(value)) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)
                    && current != '\n' && current != '\r' && current != '\t') {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "material_text_contains_control_character", "材料文本包含不支持的控制字符");
            }
        }
        return value.trim();
    }

    private record ExistingMaterial(UUID id, String requestHash) {
    }
}
