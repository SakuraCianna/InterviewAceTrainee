package icu.sakuracianna.mianba.interview.packageflow;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 在单个数据库事务中创建工作面试套餐、固定阶段与首场技术面试。 */
@Service
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class JdbcJobInterviewPackageService implements JobInterviewPackageService {
    private static final String END_RULE = "MIN_TURNS_AND_COVERAGE_AND_MODEL_OR_MAX_TURNS";
    private static final String FIRST_ROUND = "技术一面 · 自我介绍";
    private static final String FIRST_QUESTION = "请用两分钟介绍与你目标岗位最相关的一段经历。";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcJobInterviewPackageService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public JobInterviewPackageView create(
            UUID userId,
            UUID packageId,
            UUID firstSessionId,
            UUID materialId,
            String idempotencyKey) {
        requireCreateArguments(userId, packageId, firstSessionId, materialId, idempotencyKey);
        String requestHash = requestHash(packageId, firstSessionId, materialId);

        Optional<JobInterviewPackageView> replay = replay(
                userId, packageId, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        UserBilling user = jdbc.query("""
                SELECT credit_balance, role
                FROM users
                WHERE id = ? AND is_active = true
                FOR UPDATE
                """, (rs, row) -> new UserBilling(
                rs.getInt("credit_balance"), rs.getString("role")), userId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "user_not_found", "用户不存在或不可用"));

        // 用户锁等待期间，先到请求可能已经提交；锁后重查以避免重复计费。
        replay = replay(userId, packageId, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        MaterialRow material = jdbc.query("""
                SELECT profile_summary, job_title, job_requirements,
                       keywords::text AS keywords_json
                FROM materials
                WHERE id = ? AND user_id = ?
                  AND status = 'ready' AND interview_type = 'job'
                """, (rs, row) -> new MaterialRow(
                rs.getString("profile_summary"),
                rs.getString("job_title"),
                rs.getString("job_requirements"),
                rs.getString("keywords_json")), materialId, userId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "interview_material_not_found",
                        "工作面试材料不存在或尚未准备好"));

        JobInterviewPlan plan = JobInterviewPlan.chineseEnterpriseV1();
        PreparedJson preparedJson = prepareJson(material, plan);
        boolean adminUnlimited = "admin".equals(user.role());
        UUID voucherId = null;
        if (!adminUnlimited) {
            voucherId = jdbc.query("""
                    SELECT id
                    FROM vouchers
                    WHERE user_id = ?
                      AND status = 'available'
                      AND remaining_uses > 0
                      AND (scope_interview_type IS NULL OR scope_interview_type = 'job')
                      AND (expires_at IS NULL OR expires_at > now())
                    ORDER BY expires_at NULLS LAST, created_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> rs.getObject("id", UUID.class), userId)
                    .stream().findFirst().orElse(null);
            if (voucherId == null && user.balance() < 3) {
                throw new ApiException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "insufficient_credits",
                        "可用训练次数不足，请联系管理员开通");
            }
        }

        int chargedCredit = !adminUnlimited && voucherId == null ? 3 : 0;
        Instant now = clock.instant();
        Instant packageExpiresAt = now.plus(30, ChronoUnit.DAYS);
        Instant firstSessionExpiresAt = min(
                now.plus(24, ChronoUnit.HOURS), packageExpiresAt);
        Timestamp nowTimestamp = Timestamp.from(now);

        insertPackage(
                userId,
                packageId,
                materialId,
                voucherId,
                idempotencyKey,
                requestHash,
                chargedCredit,
                adminUnlimited,
                preparedJson.materialSnapshot(),
                packageExpiresAt,
                nowTimestamp);
        insertStages(packageId, plan, preparedJson.planSnapshots(), nowTimestamp);
        applyBilling(
                userId,
                packageId,
                voucherId,
                chargedCredit,
                user.balance(),
                nowTimestamp);
        insertFirstSession(
                userId,
                packageId,
                firstSessionId,
                materialId,
                plan.stage(JobInterviewStage.TECHNICAL_FIRST).maxTurns(),
                adminUnlimited,
                nowTimestamp,
                Timestamp.from(firstSessionExpiresAt));
        bindFirstStage(packageId, firstSessionId, nowTimestamp);
        insertFirstTurn(firstSessionId, nowTimestamp);
        return get(userId, packageId);
    }

    @Override
    public Optional<JobInterviewPackageView> active(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return jdbc.query("""
                SELECT id
                FROM interview_packages
                WHERE user_id = ? AND status = 'ACTIVE' AND expires_at > now()
                ORDER BY created_at DESC
                LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), userId)
                .stream().findFirst().map(packageId -> get(userId, packageId));
    }

    @Override
    public JobInterviewPackageView get(UUID userId, UUID packageId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(packageId, "packageId");
        PackageRow packageRow = jdbc.query("""
                SELECT p.id, p.status, p.current_stage_code, p.charged_credit,
                       p.admin_unlimited_usage, p.expires_at
                FROM interview_packages p
                WHERE p.id = ? AND p.user_id = ?
                """, this::mapPackage, packageId, userId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "job_interview_package_not_found",
                        "工作面试套餐不存在"));
        List<JobInterviewPackageView.Stage> stages = jdbc.query("""
                SELECT s.stage_code, s.sequence_no, s.status, s.session_id,
                       s.min_turns, s.max_turns, s.target_duration_minutes,
                       s.plan_snapshot -> 'required_sections' AS required_sections_json
                FROM interview_package_stages s
                JOIN interview_packages p ON p.id = s.package_id
                WHERE s.package_id = ? AND p.user_id = ?
                ORDER BY s.sequence_no
                """, this::mapStage, packageId, userId);
        return new JobInterviewPackageView(
                packageRow.id(),
                packageRow.status(),
                packageRow.currentStageCode(),
                packageRow.chargedCredit(),
                packageRow.adminUnlimitedUsage(),
                packageRow.expiresAt(),
                stages);
    }

    @Override
    public JobInterviewPackageView startStage(
            UUID userId,
            UUID packageId,
            JobInterviewStage stage,
            UUID sessionId,
            String idempotencyKey) {
        throw new ApiException(
                HttpStatus.CONFLICT,
                "job_interview_stage_start_unavailable",
                "当前版本暂不支持启动后续面试阶段");
    }

    private Optional<JobInterviewPackageView> replay(
            UUID userId,
            UUID requestedPackageId,
            String idempotencyKey,
            String requestHash) {
        Optional<ExistingPackage> existing = jdbc.query("""
                SELECT id, request_hash
                FROM interview_packages
                WHERE user_id = ? AND start_idempotency_key = ?
                """, (rs, row) -> new ExistingPackage(
                rs.getObject("id", UUID.class), rs.getString("request_hash")), userId, idempotencyKey)
                .stream().findFirst();
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ExistingPackage value = existing.get();
        if (!value.id().equals(requestedPackageId) || !value.requestHash().equals(requestHash)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "idempotency_key_conflict",
                    "同一幂等键不能用于不同的工作面试套餐请求");
        }
        return Optional.of(get(userId, value.id()));
    }

    private PreparedJson prepareJson(MaterialRow material, JobInterviewPlan plan) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            putNormalized(snapshot, "profile_summary", material.profileSummary(), 1200);
            putNormalized(snapshot, "job_title", material.jobTitle(), 160);
            putNormalized(snapshot, "job_requirements_summary", material.jobRequirements(), 2500);
            List<String> keywords = safeKeywords(material.keywordsJson());
            if (!keywords.isEmpty()) {
                snapshot.put("keywords", keywords);
            }
            String materialSnapshot = mapper.writeValueAsString(snapshot);

            List<String> stageSnapshots = new ArrayList<>(plan.stages().size());
            for (JobInterviewPlan.StagePlan stage : plan.stages()) {
                Map<String, Object> stageSnapshot = new LinkedHashMap<>();
                stageSnapshot.put("stage_code", stage.code().name());
                stageSnapshot.put("sequence", stage.code().sequence());
                stageSnapshot.put("min_turns", stage.minTurns());
                stageSnapshot.put("max_turns", stage.maxTurns());
                stageSnapshot.put("target_duration_minutes", stage.targetMinutes());
                stageSnapshot.put("required_sections", stage.requiredSections());
                stageSnapshot.put("end_rule", END_RULE);
                stageSnapshots.add(mapper.writeValueAsString(stageSnapshot));
            }
            return new PreparedJson(materialSnapshot, List.copyOf(stageSnapshots));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to build safe job interview package snapshots", exception);
        }
    }

    private List<String> safeKeywords(String keywordsJson) throws JacksonException {
        if (keywordsJson == null || keywordsJson.isBlank()) {
            return List.of();
        }
        JsonNode root = mapper.readTree(keywordsJson);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Material keywords must be a JSON array");
        }
        List<String> keywords = new ArrayList<>(12);
        Set<String> seen = new HashSet<>();
        for (JsonNode value : root) {
            if (!value.isString()) {
                continue;
            }
            String normalized = normalize(value.stringValue(), 24);
            if (!normalized.isEmpty() && seen.add(normalized)) {
                keywords.add(normalized);
                if (keywords.size() == 12) {
                    break;
                }
            }
        }
        return List.copyOf(keywords);
    }

    private static void putNormalized(
            Map<String, Object> target,
            String key,
            String rawValue,
            int maximumCodePoints) {
        String normalized = normalize(rawValue, maximumCodePoints);
        if (!normalized.isEmpty()) {
            target.put(key, normalized);
        }
    }

    static String normalize(String rawValue, int maximumCodePoints) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        boolean pendingSpace = false;
        for (int offset = 0; offset < rawValue.length();) {
            int codePoint = rawValue.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        String result = normalized.toString();
        int codePointCount = result.codePointCount(0, result.length());
        if (codePointCount <= maximumCodePoints) {
            return result;
        }
        return result.substring(0, result.offsetByCodePoints(0, maximumCodePoints));
    }

    private void insertPackage(
            UUID userId,
            UUID packageId,
            UUID materialId,
            UUID voucherId,
            String idempotencyKey,
            String requestHash,
            int chargedCredit,
            boolean adminUnlimited,
            String materialSnapshot,
            Instant packageExpiresAt,
            Timestamp now) {
        try {
            jdbc.update("""
                    INSERT INTO interview_packages(
                        id, user_id, material_id, voucher_id, start_idempotency_key,
                        request_hash, status, current_stage_code, charged_credit,
                        admin_unlimited_usage, plan_version, rubric_version,
                        material_snapshot, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'TECHNICAL_FIRST', ?, ?,
                            'job-cn-v1', 'job-rubric-v1', ?::jsonb, ?, ?, ?)
                    """,
                    packageId,
                    userId,
                    materialId,
                    voucherId,
                    idempotencyKey,
                    requestHash,
                    chargedCredit,
                    adminUnlimited,
                    materialSnapshot,
                    Timestamp.from(packageExpiresAt),
                    now,
                    now);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "job_interview_package_conflict",
                    "已有工作面试套餐或请求标识发生冲突");
        }
    }

    private void insertStages(
            UUID packageId,
            JobInterviewPlan plan,
            List<String> planSnapshots,
            Timestamp now) {
        try {
            for (int index = 0; index < plan.stages().size(); index++) {
                JobInterviewPlan.StagePlan stage = plan.stages().get(index);
                String status = index == 0 ? "UNLOCKED" : "LOCKED";
                Timestamp unlockedAt = index == 0 ? now : null;
                jdbc.update("""
                        INSERT INTO interview_package_stages(
                            package_id, stage_code, sequence_no, status,
                            plan_snapshot, context_snapshot,
                            min_turns, max_turns, target_duration_minutes,
                            unlocked_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, '{}'::jsonb, ?, ?, ?, ?, ?, ?)
                        """,
                        packageId,
                        stage.code().name(),
                        stage.code().sequence(),
                        status,
                        planSnapshots.get(index),
                        stage.minTurns(),
                        stage.maxTurns(),
                        stage.targetMinutes(),
                        unlockedAt,
                        now,
                        now);
            }
        } catch (DataIntegrityViolationException exception) {
            throw aggregateConflict();
        }
    }

    private void applyBilling(
            UUID userId,
            UUID packageId,
            UUID voucherId,
            int chargedCredit,
            int originalBalance,
            Timestamp now) {
        try {
            if (voucherId != null) {
                int updated = jdbc.update("""
                        UPDATE vouchers
                        SET remaining_uses = 0, status = 'redeemed',
                            redeemed_package_id = ?, redeemed_session_id = NULL,
                            redeemed_at = ?, version = version + 1
                        WHERE id = ?
                        """, packageId, now, voucherId);
                if (updated != 1) {
                    throw aggregateConflict();
                }
                return;
            }
            if (chargedCredit == 0) {
                return;
            }
            int updated = jdbc.update("""
                    UPDATE users
                    SET credit_balance = credit_balance - ?,
                        version = version + 1, updated_at = now()
                    WHERE id = ?
                    """, chargedCredit, userId);
            if (updated != 1) {
                throw aggregateConflict();
            }
            jdbc.update("""
                    INSERT INTO credit_ledger(
                        user_id, change_amount, balance_after, reason,
                        idempotency_key, related_session_id, related_package_id, created_at)
                    VALUES (?, ?, ?, 'interview_package_start', ?, NULL, ?, ?)
                    """,
                    userId,
                    -chargedCredit,
                    originalBalance - chargedCredit,
                    "interview-package-start:" + packageId,
                    packageId,
                    now);
        } catch (DataIntegrityViolationException exception) {
            throw aggregateConflict();
        }
    }

    private void insertFirstSession(
            UUID userId,
            UUID packageId,
            UUID firstSessionId,
            UUID materialId,
            int totalTurns,
            boolean adminUnlimited,
            Timestamp now,
            Timestamp expiresAt) {
        try {
            jdbc.update("""
                    INSERT INTO sessions(
                        id, user_id, start_idempotency_key, material_id, interview_type,
                        status, current_turn_index, total_turns, charged_credit,
                        voucher_id, admin_unlimited_usage, started_at,
                        expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'job', 'active', 0, ?, 0,
                            NULL, ?, ?, ?, ?, ?)
                    """,
                    firstSessionId,
                    userId,
                    "job-package-start:" + packageId,
                    materialId,
                    totalTurns,
                    adminUnlimited,
                    now,
                    expiresAt,
                    now,
                    now);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "active_interview_exists",
                    "已有一场未完成的面试训练");
        }
    }

    private void bindFirstStage(UUID packageId, UUID firstSessionId, Timestamp now) {
        try {
            int updated = jdbc.update("""
                    UPDATE interview_package_stages
                    SET status = 'IN_PROGRESS', session_id = ?, started_at = ?,
                        version = version + 1, updated_at = ?
                    WHERE package_id = ? AND stage_code = 'TECHNICAL_FIRST'
                      AND status = 'UNLOCKED'
                    """, firstSessionId, now, now, packageId);
            if (updated != 1) {
                throw aggregateConflict();
            }
        } catch (DataIntegrityViolationException exception) {
            throw aggregateConflict();
        }
    }

    private void insertFirstTurn(UUID firstSessionId, Timestamp now) {
        try {
            jdbc.update("""
                    INSERT INTO turns(
                        session_id, turn_index, round_name, question_text, status,
                        section_code, question_type, topic_code, parent_turn_id, created_at)
                    VALUES (?, 0, ?, ?, 'waiting_answer',
                            'INTRODUCTION', 'INTRODUCTION', 'self_introduction', NULL, ?)
                    """, firstSessionId, FIRST_ROUND, FIRST_QUESTION, now);
        } catch (DataIntegrityViolationException exception) {
            throw aggregateConflict();
        }
    }

    private PackageRow mapPackage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PackageRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("status"),
                JobInterviewStage.valueOf(resultSet.getString("current_stage_code")),
                resultSet.getInt("charged_credit"),
                resultSet.getBoolean("admin_unlimited_usage"),
                resultSet.getTimestamp("expires_at").toInstant());
    }

    private JobInterviewPackageView.Stage mapStage(ResultSet resultSet, int rowNumber)
            throws SQLException {
        try {
            return new JobInterviewPackageView.Stage(
                    JobInterviewStage.valueOf(resultSet.getString("stage_code")),
                    resultSet.getInt("sequence_no"),
                    resultSet.getString("status"),
                    resultSet.getObject("session_id", UUID.class),
                    resultSet.getInt("min_turns"),
                    resultSet.getInt("max_turns"),
                    resultSet.getInt("target_duration_minutes"),
                    parseRequiredSections(resultSet.getString("required_sections_json")));
        } catch (JacksonException exception) {
            throw new SQLException("Invalid interview stage required sections JSON", exception);
        }
    }

    private List<String> parseRequiredSections(String json) throws JacksonException {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Stage required sections must be a JSON array");
        }
        List<String> sections = new ArrayList<>();
        for (JsonNode value : root) {
            if (!value.isString()) {
                throw new IllegalStateException("Stage required section must be text");
            }
            sections.add(value.stringValue());
        }
        return List.copyOf(sections);
    }

    private static void requireCreateArguments(
            UUID userId,
            UUID packageId,
            UUID firstSessionId,
            UUID materialId,
            String idempotencyKey) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(firstSessionId, "firstSessionId");
        Objects.requireNonNull(materialId, "materialId");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_failed",
                    "幂等键长度必须在 1 到 128 字符之间");
        }
    }

    private static String requestHash(UUID packageId, UUID firstSessionId, UUID materialId) {
        return sha256(canonical(packageId, firstSessionId, materialId));
    }

    private static String canonical(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String component = value.toString();
            canonical.append(component.length()).append(':').append(component).append(';');
        }
        return canonical.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static ApiException aggregateConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "job_interview_package_conflict",
                "工作面试套餐状态发生冲突");
    }

    private record ExistingPackage(UUID id, String requestHash) {
    }

    private record UserBilling(int balance, String role) {
    }

    private record MaterialRow(
            String profileSummary,
            String jobTitle,
            String jobRequirements,
            String keywordsJson) {
    }

    private record PreparedJson(String materialSnapshot, List<String> planSnapshots) {
    }

    private record PackageRow(
            UUID id,
            String status,
            JobInterviewStage currentStageCode,
            int chargedCredit,
            boolean adminUnlimitedUsage,
            Instant expiresAt) {
    }
}
