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

/** 在单个数据库事务中创建工作面试套餐，并按需启动套餐阶段。 */
@Service
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class JdbcJobInterviewPackageService implements JobInterviewPackageService {
    private static final String END_RULE = "MIN_TURNS_AND_COVERAGE_AND_MODEL_OR_MAX_TURNS";
    private static final String FIRST_ROUND = "技术一面 · 自我介绍";
    private static final String FIRST_QUESTION = "请用两分钟介绍与你目标岗位最相关的一段经历。";
    private static final StageOpening SECOND_STAGE_OPENING = new StageOpening(
            "技术二面 · 项目深挖",
            "请选一个最能体现你技术深度的项目，先说明背景、目标和你负责的核心部分。",
            "PROJECT_DEEP_DIVE",
            "PROJECT_DEEP_DIVE",
            "project_overview");
    private static final StageOpening HR_STAGE_OPENING = new StageOpening(
            "HR 面 · 求职动机",
            "结合前两轮面试体验，请先说明你选择这个岗位和公司的主要动机。",
            "MOTIVATION",
            "HR_COMPREHENSIVE",
            "job_motivation");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcJobInterviewPackageService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 固定按 Package→Stage 顺序加锁，使阶段绑定与幂等重放共享同一事务事实。 */
    @Override
    @Transactional
    public JobInterviewPackageView create(
            UUID userId,
            UUID packageId,
            UUID firstSessionId,
            UUID materialId,
            String idempotencyKey) {
        throw new ApiException(HttpStatus.GONE,
                "persistent_material_flow_removed", "材料持久化流程已下线，请重新提交临时材料");
    }

    @Override
    @Transactional
    public JobInterviewPackageView createPersonalized(
            UUID userId,
            UUID packageId,
            UUID firstSessionId,
            String idempotencyKey,
            String openingQuestion) {
        requireCreateArguments(userId, packageId, firstSessionId, idempotencyKey);
        String safeOpeningQuestion = normalizeOpeningQuestion(openingQuestion);
        String requestHash = requestHash(packageId, firstSessionId, safeOpeningQuestion);

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

        JobInterviewPlan plan = JobInterviewPlan.chineseEnterpriseV1();
        List<String> planSnapshots = preparePlanJson(plan);
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

        jdbc.update("""
                UPDATE interview_packages
                SET status = 'CANCELLED', updated_at = ?
                WHERE user_id = ? AND status = 'ACTIVE'
                """, nowTimestamp, userId);

        insertPackage(
                userId,
                packageId,
                voucherId,
                idempotencyKey,
                requestHash,
                chargedCredit,
                adminUnlimited,
                packageExpiresAt,
                nowTimestamp);
        insertStages(packageId, plan, planSnapshots, nowTimestamp);
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
                plan.stage(JobInterviewStage.TECHNICAL_FIRST).maxTurns(),
                adminUnlimited,
                nowTimestamp,
                Timestamp.from(firstSessionExpiresAt));
        bindFirstStage(packageId, firstSessionId, nowTimestamp);
        insertFirstTurn(firstSessionId, safeOpeningQuestion, nowTimestamp);
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
    @Transactional
    public JobInterviewPackageView startStage(
            UUID userId,
            UUID packageId,
            JobInterviewStage stage,
            UUID sessionId,
            String idempotencyKey) {
        requireStartStageArguments(userId, packageId, stage, sessionId, idempotencyKey);
        LockedPackage lockedPackage = lockPackage(userId, packageId);
        Instant now = clock.instant();
        requireStartablePackage(lockedPackage, stage, now);

        LockedStage lockedStage = lockStage(packageId, stage);
        if (lockedStage.sessionId() != null) {
            return replayStartedStage(
                    userId, packageId, lockedStage.sessionId(), sessionId, idempotencyKey);
        }
        if (stage == JobInterviewStage.TECHNICAL_FIRST) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "job_interview_stage_already_started",
                    "技术一面只能随套餐创建启动");
        }
        if (!"UNLOCKED".equals(lockedStage.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "job_interview_stage_not_unlocked",
                    "当前面试阶段尚未解锁");
        }

        Instant sessionExpiresAt = min(now.plus(24, ChronoUnit.HOURS), lockedPackage.expiresAt());
        if (!sessionExpiresAt.isAfter(now)) {
            throw new ApiException(
                    HttpStatus.GONE,
                    "job_interview_package_expired",
                    "工作面试套餐已过期");
        }
        Timestamp nowTimestamp = Timestamp.from(now);
        insertLaterSession(
                userId,
                sessionId,
                idempotencyKey,
                lockedStage.maxTurns(),
                nowTimestamp,
                Timestamp.from(sessionExpiresAt));
        bindLaterStage(packageId, stage, sessionId, nowTimestamp);
        insertLaterTurn(sessionId, openingFor(stage), nowTimestamp);
        return get(userId, packageId);
    }

    private LockedPackage lockPackage(UUID userId, UUID packageId) {
        return jdbc.query("""
                SELECT status, current_stage_code, expires_at
                FROM interview_packages
                WHERE id = ? AND user_id = ?
                FOR UPDATE
                """, (rs, row) -> new LockedPackage(
                rs.getString("status"),
                JobInterviewStage.valueOf(rs.getString("current_stage_code")),
                rs.getTimestamp("expires_at").toInstant()), packageId, userId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "job_interview_package_not_found",
                        "工作面试套餐不存在"));
    }

    private static void requireStartablePackage(
            LockedPackage lockedPackage,
            JobInterviewStage stage,
            Instant now) {
        if (!"ACTIVE".equals(lockedPackage.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "job_interview_package_not_active",
                    "工作面试套餐当前不可启动阶段");
        }
        if (!lockedPackage.expiresAt().isAfter(now)) {
            throw new ApiException(
                    HttpStatus.GONE,
                    "job_interview_package_expired",
                    "工作面试套餐已过期");
        }
        if (lockedPackage.currentStage() != stage) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "job_interview_stage_not_current",
                    "只能启动当前面试阶段");
        }
    }

    private LockedStage lockStage(UUID packageId, JobInterviewStage stage) {
        return jdbc.query("""
                SELECT status, session_id, max_turns
                FROM interview_package_stages
                WHERE package_id = ? AND stage_code = ?
                FOR UPDATE
                """, (rs, row) -> new LockedStage(
                rs.getString("status"),
                rs.getObject("session_id", UUID.class),
                rs.getInt("max_turns")), packageId, stage.name())
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "job_interview_stage_not_found",
                        "工作面试阶段不存在"));
    }

    private JobInterviewPackageView replayStartedStage(
            UUID userId,
            UUID packageId,
            UUID boundSessionId,
            UUID requestedSessionId,
            String idempotencyKey) {
        Optional<String> boundKey = jdbc.query("""
                SELECT start_idempotency_key
                FROM sessions
                WHERE id = ?
                """, (rs, row) -> rs.getString("start_idempotency_key"), boundSessionId)
                .stream().findFirst();
        if (!boundSessionId.equals(requestedSessionId)
                || boundKey.isEmpty()
                || !boundKey.get().equals(idempotencyKey)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "idempotency_key_conflict",
                    "已启动阶段不能绑定其他会话或幂等键");
        }
        return get(userId, packageId);
    }

    private void insertLaterSession(
            UUID userId,
            UUID sessionId,
            String idempotencyKey,
            int totalTurns,
            Timestamp now,
            Timestamp expiresAt) {
        try {
            jdbc.update("""
                    INSERT INTO sessions(
                        id, user_id, start_idempotency_key, interview_type,
                        status, current_turn_index, total_turns, charged_credit,
                        voucher_id, admin_unlimited_usage, started_at,
                        expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'job', 'active', 0, ?, 0,
                            NULL, false, ?, ?, ?, ?)
                    """,
                    sessionId,
                    userId,
                    idempotencyKey,
                    totalTurns,
                    now,
                    expiresAt,
                    now,
                    now);
        } catch (DataIntegrityViolationException exception) {
            throw aggregateConflict();
        }
    }

    private void bindLaterStage(
            UUID packageId,
            JobInterviewStage stage,
            UUID sessionId,
            Timestamp now) {
        try {
            int updated = jdbc.update("""
                    UPDATE interview_package_stages
                    SET status = 'IN_PROGRESS', session_id = ?, started_at = ?,
                        version = version + 1, updated_at = ?
                    WHERE package_id = ? AND stage_code = ?
                      AND status = 'UNLOCKED' AND session_id IS NULL
                    """, sessionId, now, now, packageId, stage.name());
            if (updated != 1) {
                throw aggregateConflict();
            }
        } catch (DataIntegrityViolationException exception) {
            throw aggregateConflict();
        }
    }

    private void insertLaterTurn(UUID sessionId, StageOpening opening, Timestamp now) {
        try {
            jdbc.update("""
                    INSERT INTO turns(
                        session_id, turn_index, round_name, question_text, status,
                        section_code, question_type, topic_code, parent_turn_id, created_at)
                    VALUES (?, 0, ?, ?, 'waiting_answer', ?, ?, ?, NULL, ?)
                    """,
                    sessionId,
                    opening.roundName(),
                    opening.question(),
                    opening.sectionCode(),
                    opening.questionType(),
                    opening.topicCode(),
                    now);
        } catch (DataIntegrityViolationException exception) {
            throw aggregateConflict();
        }
    }

    private static StageOpening openingFor(JobInterviewStage stage) {
        return switch (stage) {
            case TECHNICAL_SECOND -> SECOND_STAGE_OPENING;
            case HR_FINAL -> HR_STAGE_OPENING;
            case TECHNICAL_FIRST -> throw new IllegalArgumentException(
                    "Technical first stage is created with the package");
        };
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

    private List<String> preparePlanJson(JobInterviewPlan plan) {
        try {
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
            return List.copyOf(stageSnapshots);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to build job interview plan snapshots", exception);
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
            UUID voucherId,
            String idempotencyKey,
            String requestHash,
            int chargedCredit,
            boolean adminUnlimited,
            Instant packageExpiresAt,
            Timestamp now) {
        try {
            jdbc.update("""
                    INSERT INTO interview_packages(
                        id, user_id, voucher_id, start_idempotency_key,
                        request_hash, status, current_stage_code, charged_credit,
                        admin_unlimited_usage, plan_version, rubric_version,
                        expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'TECHNICAL_FIRST', ?, ?,
                            'job-cn-v1', 'job-rubric-v1', ?, ?, ?)
                    """,
                    packageId,
                    userId,
                    voucherId,
                    idempotencyKey,
                    requestHash,
                    chargedCredit,
                    adminUnlimited,
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
            int totalTurns,
            boolean adminUnlimited,
            Timestamp now,
            Timestamp expiresAt) {
        try {
            jdbc.update("""
                    INSERT INTO sessions(
                        id, user_id, start_idempotency_key, interview_type,
                        status, current_turn_index, total_turns, charged_credit,
                        voucher_id, admin_unlimited_usage, started_at,
                        expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'job', 'active', 0, ?, 0,
                            NULL, ?, ?, ?, ?, ?)
                    """,
                    firstSessionId,
                    userId,
                    "job-package-start:" + packageId,
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

    private void insertFirstTurn(UUID firstSessionId, String openingQuestion, Timestamp now) {
        try {
            jdbc.update("""
                    INSERT INTO turns(
                        session_id, turn_index, round_name, question_text, status,
                        section_code, question_type, topic_code, parent_turn_id, created_at)
                    VALUES (?, 0, ?, ?, 'waiting_answer',
                            'INTRODUCTION', 'INTRODUCTION', 'self_introduction', NULL, ?)
                    """, firstSessionId, FIRST_ROUND, openingQuestion, now);
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
            String idempotencyKey) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(firstSessionId, "firstSessionId");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_failed",
                    "幂等键长度必须在 1 到 128 字符之间");
        }
    }

    private static void requireStartStageArguments(
            UUID userId,
            UUID packageId,
            JobInterviewStage stage,
            UUID sessionId,
            String idempotencyKey) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(sessionId, "sessionId");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_failed",
                    "幂等键长度必须在 1 到 128 字符之间");
        }
    }

    private static String requestHash(UUID packageId, UUID firstSessionId, String openingQuestion) {
        return sha256(canonical(packageId, firstSessionId, openingQuestion));
    }

    private static String normalizeOpeningQuestion(String question) {
        String normalized = normalize(question, 800);
        if (normalized.length() < 8) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "personalized_question_invalid", "个性化首题格式不正确");
        }
        return normalized;
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

    private record LockedPackage(
            String status,
            JobInterviewStage currentStage,
            Instant expiresAt) {
    }

    private record LockedStage(String status, UUID sessionId, int maxTurns) {
    }

    private record StageOpening(
            String roundName,
            String question,
            String sectionCode,
            String questionType,
            String topicCode) {
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
