package icu.sakuracianna.mianba.interview.material;

import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.interview.safety.AnswerSafetyPolicy;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyAuditService;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 请求级材料服务：只在堆内短暂解析，不持久化材料原文，仅写入脱敏风控审计。 */
@Service
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public final class TransientMaterialService implements MaterialService {
    private static final int MAX_MATERIAL_REQUESTS_PER_DAY = 10;
    private static final int MAX_QUERY_CODE_POINTS = 8_000;

    private final AbuseProtection abuseProtection;
    private final MaterialParserClient parserClient;
    private final AnswerSafetyPolicy safetyPolicy;
    private final ContentSafetyAuditService safetyAudits;
    private final Semaphore capacity = new Semaphore(1, true);

    public TransientMaterialService(
            AbuseProtection abuseProtection,
            MaterialParserClient parserClient,
            AnswerSafetyPolicy safetyPolicy,
            ContentSafetyAuditService safetyAudits) {
        this.abuseProtection = abuseProtection;
        this.parserClient = parserClient;
        this.safetyPolicy = safetyPolicy;
        this.safetyAudits = safetyAudits;
    }

    @Override
    public EphemeralMaterial analyze(
            UUID userId,
            String requestId,
            String interviewType,
            MultipartFile resumeFile,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major,
            String researchDirection) {
        String type = normalizeType(interviewType);
        validateRequiredFields(type, resumeFile, jobTitle, jobRequirements, targetSchool, major);
        assessField(userId, requestId, "job_title", jobTitle);
        assessField(userId, requestId, "job_requirements", jobRequirements);
        assessField(userId, requestId, "target_school", targetSchool);
        assessField(userId, requestId, "major", major);
        assessField(userId, requestId, "research_direction", researchDirection);
        abuseProtection.check("material-create-daily", userId.toString(),
                MAX_MATERIAL_REQUESTS_PER_DAY, Duration.ofDays(1));
        if (!capacity.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "material_upload_capacity_full", "材料解析繁忙，请稍后重试");
        }
        byte[] payload = null;
        try {
            String parsedText = "";
            if (resumeFile != null && !resumeFile.isEmpty()) {
                payload = readBytes(resumeFile);
                String filename = MaterialFilename.sanitize(resumeFile.getOriginalFilename());
                String contentType = resumeFile.getContentType();
                ParsedMaterial parsed = parserClient.parse(filename, contentType, payload);
                parsedText = normalizeText(parsed.text(), MaterialUploadPolicy.DEFAULT_MAX_TEXT_CHARS);
                if (parsedText.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "resume_text_empty", "上传材料没有提取到可用文本");
                }
                assessField(userId, requestId, "interview_material", parsedText);
            }
            String query = buildQuery(type, parsedText, jobTitle, jobRequirements,
                    targetSchool, major, researchDirection);
            char[] characters = query.toCharArray();
            return new EphemeralMaterial(type, characters);
        } finally {
            if (payload != null) {
                Arrays.fill(payload, (byte) 0);
            }
            capacity.release();
        }
    }

    private void assessField(
            UUID userId,
            String requestId,
            String source,
            String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        safetyPolicy.assess(value).ifPresent(finding -> {
            // 个性化材料在会话创建前检查，因此不能引用尚不存在的 sessions 外键。
            safetyAudits.recordInput(userId, null, requestId, source, value, finding);
            if (finding.blocked()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "unsafe_" + source, "输入包含不安全指令，已停止处理");
            }
        });
    }

    private static void validateRequiredFields(
            String type,
            MultipartFile resumeFile,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major) {
        if ("job".equals(type)) {
            if (resumeFile == null || resumeFile.isEmpty()
                    || blank(jobTitle) || blank(jobRequirements)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "job_material_required", "岗位面试需要简历、目标岗位和岗位要求");
            }
            requireLength(jobTitle, 160, "job_title");
            requireLength(jobRequirements, 8_000, "job_requirements");
            return;
        }
        if ("postgraduate".equals(type)) {
            if (blank(targetSchool) || blank(major)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "postgraduate_material_required", "考研复试需要目标院校和报考专业");
            }
            requireLength(targetSchool, 160, "target_school");
            requireLength(major, 160, "major");
            return;
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "personalized_material_not_supported", "该面试类型不接受个性化材料");
    }

    private static String buildQuery(
            String type,
            String parsedText,
            String jobTitle,
            String jobRequirements,
            String targetSchool,
            String major,
            String researchDirection) {
        String query = "job".equals(type)
                ? String.join("\n", "目标岗位：" + safe(jobTitle),
                        "岗位要求：" + safe(jobRequirements), "候选经历：" + parsedText)
                : String.join("\n", "目标院校：" + safe(targetSchool),
                        "报考专业：" + safe(major), "研究方向：" + safe(researchDirection),
                        "补充经历：" + parsedText);
        return normalizeText(query, MAX_QUERY_CODE_POINTS);
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file.getSize() < 0 || file.getSize() > MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE,
                    "resume_file_too_large", "简历文件不能超过 5 MiB");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES) {
                Arrays.fill(bytes, (byte) 0);
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE,
                        "resume_file_too_large", "简历文件不能超过 5 MiB");
            }
            return bytes;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "resume_read_failed", "无法读取上传材料");
        }
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value, int maximumCodePoints) {
        String normalized = value == null ? "" : value
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").trim();
        int count = normalized.codePointCount(0, normalized.length());
        return count <= maximumCodePoints
                ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, maximumCodePoints));
    }

    private static void requireLength(String value, int maximum, String field) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_failed", field + " 长度超过限制");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
