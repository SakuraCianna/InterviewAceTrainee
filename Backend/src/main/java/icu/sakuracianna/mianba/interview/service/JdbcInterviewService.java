package icu.sakuracianna.mianba.interview.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import icu.sakuracianna.mianba.aiwork.messaging.AiJobEnvelope;
import icu.sakuracianna.mianba.aiwork.service.TaskService;
import icu.sakuracianna.mianba.aiwork.service.TaskView;
import icu.sakuracianna.mianba.interview.domain.InterviewType;
import icu.sakuracianna.mianba.interview.safety.AnswerSafetyPolicy;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyAuditService;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 PostgreSQL 的面试事务服务，负责计费/券核销、轮次状态与 AI Outbox 的一致性。
 *
 * 启动和回答提交会锁定用户或会话行；数据库提交前只写业务状态与 Outbox，
 * 不直接发布 RabbitMQ，避免消息先到而事务数据尚不可见。
 */
@Service
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class JdbcInterviewService implements InterviewService {
    private static final Map<InterviewType, InterviewPlan> PLANS = Map.of(
            InterviewType.JOB, new InterviewPlan(6, 2, "岗位开场", "请用两分钟介绍与你目标岗位最相关的一段经历。"),
            InterviewType.POSTGRADUATE, new InterviewPlan(5, 1, "复试开场", "请介绍你的专业基础，以及选择该研究方向的原因。"),
            InterviewType.CIVIL_SERVICE, new InterviewPlan(5, 1, "综合分析", "请结合实际，分析公共服务中效率与公平应如何平衡。"),
            InterviewType.IELTS, new InterviewPlan(6, 2, "Part 1 · Introduction", "Please introduce yourself and describe a skill you want to improve."));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TaskService tasks;
    private final AnswerSafetyPolicy safetyPolicy;
    private final ContentSafetyAuditService safetyAudits;
    private final Clock clock;
    private final SessionDeletionCoordinator deletions;

    @Autowired
    public JdbcInterviewService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            TaskService tasks,
            AnswerSafetyPolicy safetyPolicy,
            ContentSafetyAuditService safetyAudits,
            Clock clock,
            SessionDeletionCoordinator deletions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tasks = tasks;
        this.safetyPolicy = safetyPolicy;
        this.safetyAudits = safetyAudits;
        this.clock = clock;
        this.deletions = deletions;
    }

    @Override
    @Transactional
    public InterviewView start(
            UUID userId,
            UUID sessionId,
            String interviewType,
            UUID materialId,
            String idempotencyKey) {
        if (materialId != null) {
            throw new ApiException(HttpStatus.GONE,
                    "persistent_material_flow_removed", "材料持久化流程已下线，请重新提交临时材料");
        }
        return startInternal(userId, sessionId, interviewType, idempotencyKey, null);
    }

    JdbcInterviewService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            TaskService tasks,
            AnswerSafetyPolicy safetyPolicy,
            Clock clock,
            SessionDeletionCoordinator deletions) {
        this(jdbc, mapper, tasks, safetyPolicy,
                new ContentSafetyAuditService(
                        jdbc, mapper,
                        new icu.sakuracianna.mianba.interview.safety.ContentSafetyProperties("")),
                clock, deletions);
    }

    @Override
    @Transactional
    public InterviewView startPersonalized(
            UUID userId,
            UUID sessionId,
            String interviewType,
            String idempotencyKey,
            String openingQuestion) {
        String normalizedQuestion = normalizeOpeningQuestion(openingQuestion);
        return startInternal(userId, sessionId, interviewType, idempotencyKey, normalizedQuestion);
    }

    private InterviewView startInternal(
            UUID userId,
            UUID sessionId,
            String interviewType,
            String idempotencyKey,
            String openingQuestion) {
        InterviewType type = parseType(interviewType);
        if (type == InterviewType.JOB) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "job_interview_package_required", "工作面试请通过套餐流程开始");
        }
        InterviewPlan plan = PLANS.get(type);
        cancelExpiredOpenSessions(userId);

        Optional<InterviewView> replay = replayStart(userId, sessionId, type, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        Instant sessionExpiresAt = clock.instant().plus(24, ChronoUnit.HOURS);

        UserBilling user = jdbc.query("""
                SELECT credit_balance, role FROM users
                WHERE id = ? AND is_active = true
                FOR UPDATE
                """, (rs, row) -> new UserBilling(rs.getInt("credit_balance"), rs.getString("role")), userId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));

        // 等待用户锁期间，先到请求可能已经提交；锁后重查可防止重复扣次或核销体验券。
        replay = replayStart(userId, sessionId, type, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        UUID voucherId = null;
        Voucher selectedVoucher = null;
        int chargedCredit = 0;
        boolean adminUnlimited = "admin".equals(user.role());
        if (!adminUnlimited) {
            Optional<Voucher> voucher = jdbc.query("""
                    SELECT id, remaining_uses
                    FROM vouchers
                    WHERE user_id = ?
                      AND status = 'available'
                      AND remaining_uses > 0
                      AND (scope_interview_type IS NULL OR scope_interview_type = ?)
                      AND (expires_at IS NULL OR expires_at > now())
                    ORDER BY expires_at NULLS LAST, created_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new Voucher(
                    rs.getObject("id", UUID.class), rs.getInt("remaining_uses")), userId, dbType(type))
                    .stream().findFirst();
            if (voucher.isPresent()) {
                selectedVoucher = voucher.get();
                voucherId = selectedVoucher.id();
            } else {
                chargedCredit = plan.creditCost();
                if (user.balance() < chargedCredit) {
                    throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "insufficient_credits",
                            "可用训练次数不足，请联系管理员开通");
                }
                jdbc.update("""
                        UPDATE users
                        SET credit_balance = credit_balance - ?, version = version + 1, updated_at = now()
                        WHERE id = ?
                        """, chargedCredit, userId);
            }
        }

        try {
            jdbc.update("""
                    INSERT INTO sessions(
                        id, user_id, start_idempotency_key, interview_type,
                        status, current_turn_index, total_turns, charged_credit,
                        voucher_id, admin_unlimited_usage, expires_at)
                    VALUES (?, ?, ?, ?, 'active', 0, ?, ?, ?, ?, ?)
                    """, sessionId, userId, idempotencyKey, dbType(type),
                    plan.totalTurns(), chargedCredit, voucherId, adminUnlimited,
                    java.sql.Timestamp.from(sessionExpiresAt));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "active_interview_exists",
                    "已有一场未完成的面试训练");
        }
        if (selectedVoucher != null) {
            if (selectedVoucher.remainingUses() == 1) {
                jdbc.update("""
                        UPDATE vouchers
                        SET remaining_uses = 0, status = 'redeemed', redeemed_session_id = ?,
                            redeemed_at = now(), version = version + 1
                        WHERE id = ?
                        """, sessionId, selectedVoucher.id());
            } else {
                jdbc.update("""
                        UPDATE vouchers
                        SET remaining_uses = remaining_uses - 1, version = version + 1
                        WHERE id = ?
                        """, selectedVoucher.id());
            }
        }
        jdbc.update("""
                INSERT INTO turns(session_id, turn_index, round_name, question_text)
                VALUES (?, 0, ?, ?)
                """, sessionId, plan.firstRound(),
                openingQuestion == null ? plan.firstQuestion() : openingQuestion);
        if (chargedCredit > 0) {
            int balanceAfter = user.balance() - chargedCredit;
            jdbc.update("""
                    INSERT INTO credit_ledger(
                        user_id, change_amount, balance_after, reason,
                        idempotency_key, related_session_id)
                    VALUES (?, ?, ?, 'interview_start', ?, ?)
                    """, userId, -chargedCredit, balanceAfter, "interview-start:" + sessionId, sessionId);
        }
        return get(userId, sessionId);
    }

    @Override
    public Optional<InterviewView> active(UUID userId) {
        return jdbc.query("""
                SELECT id FROM sessions
                WHERE user_id = ? AND status IN ('created', 'active', 'awaiting_ai')
                  AND expires_at > now()
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), userId)
                .stream().findFirst().map(id -> get(userId, id));
    }

    @Override
    public InterviewView get(UUID userId, UUID sessionId) {
        InterviewView view = jdbc.query("""
                SELECT s.id, s.interview_type, s.status, s.current_turn_index, s.total_turns,
                       s.updated_at, s.expires_at, t.turn_index, t.round_name, t.question_text,
                       r.report_json::text AS report_json
                FROM sessions s
                LEFT JOIN turns t
                  ON t.session_id = s.id AND t.turn_index = s.current_turn_index
                LEFT JOIN reports r ON r.session_id = s.id
                WHERE s.id = ? AND s.user_id = ?
                  AND s.status NOT IN ('deleting', 'deleted')
                """, this::mapView, sessionId, userId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "interview_session_not_found", "面试训练不存在"));
        return view.withActiveTask(tasks.findCurrentForOwnerSession(userId, sessionId).orElse(null));
    }

    @Override
    public SpeechContext requireSpeechContext(UUID userId, UUID sessionId) {
        SpeechState state = jdbc.query("""
                SELECT s.interview_type, s.status AS session_status, s.current_turn_index,
                       t.status AS turn_status, t.question_text,
                       (s.expires_at > now()) AS unexpired
                FROM sessions s
                JOIN turns t ON t.session_id = s.id AND t.turn_index = s.current_turn_index
                WHERE s.id = ? AND s.user_id = ?
                  AND s.status NOT IN ('deleting', 'deleted')
                """, (rs, row) -> new SpeechState(
                rs.getString("interview_type"),
                rs.getString("session_status"),
                rs.getInt("current_turn_index"),
                rs.getString("turn_status"),
                rs.getString("question_text"),
                rs.getBoolean("unexpired")), sessionId, userId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "interview_session_not_found", "面试训练不存在"));
        if (!state.unexpired()) {
            throw sessionExpired();
        }
        if (!"active".equals(state.sessionStatus()) || !"waiting_answer".equals(state.turnStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "interview_speech_not_ready", "当前面试轮次不能使用语音服务");
        }
        return new SpeechContext(state.interviewType(), state.turnIndex(), state.questionText());
    }

    @Override
    public List<InterviewHistoryView> history(UUID userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
                SELECT s.id, s.interview_type, s.status, s.current_turn_index, s.total_turns,
                       r.total_score, s.created_at
                FROM sessions s
                LEFT JOIN reports r ON r.session_id = s.id
                WHERE s.user_id = ? AND s.status NOT IN ('deleting', 'deleted')
                ORDER BY s.created_at DESC
                LIMIT ?
                """, (rs, row) -> new InterviewHistoryView(
                rs.getObject("id", UUID.class),
                rs.getString("interview_type"),
                rs.getString("status"),
                rs.getInt("current_turn_index"),
                rs.getInt("total_turns"),
                (Integer) rs.getObject("total_score"),
                rs.getTimestamp("created_at").toInstant()), userId, safeLimit);
    }

    @Override
    public void delete(UUID userId, UUID sessionId) {
        deletions.delete(userId, sessionId);
    }

    @Override
    @Transactional
    public AnswerAcceptance answer(
            UUID userId,
            UUID sessionId,
            String idempotencyKey,
            int turnIndex,
            String answerText,
            String requestId) {
        String normalizedAnswer = normalizeAnswerText(answerText);
        if (normalizedAnswer.isEmpty() || normalizedAnswer.length() > 8000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", "回答长度必须在 1 到 8000 字之间");
        }
        String requestHash = sha256(turnIndex + "\n" + normalizedAnswer);
        Optional<AnswerAcceptance> replay = replayAnswer(
                userId, sessionId, turnIndex, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        SessionForAnswer session = jdbc.query("""
                SELECT s.status, s.current_turn_index, s.total_turns,
                       (s.expires_at > now()) AS unexpired
                FROM sessions s
                WHERE s.id = ? AND s.user_id = ?
                FOR UPDATE OF s
                """, (rs, row) -> new SessionForAnswer(
                rs.getString("status"), rs.getInt("current_turn_index"), rs.getInt("total_turns"),
                rs.getBoolean("unexpired")),
                sessionId, userId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "interview_session_not_found", "面试训练不存在"));

        // 等待会话锁期间，首个请求可能已创建任务；锁后重查可避免同一答案被推进两次。
        replay = replayAnswer(userId, sessionId, turnIndex, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!session.unexpired()) {
            throw sessionExpired();
        }
        if (!"active".equals(session.status())) {
            String detail = "completed".equals(session.status())
                    ? "interview_session_already_completed"
                    : "interview_session_not_ready";
            throw new ApiException(HttpStatus.CONFLICT, detail, "当前面试状态不能提交回答");
        }
        if (turnIndex != session.currentTurnIndex()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "interview_turn_stale", "回答轮次已经变化，请刷新后重试");
        }
        UUID packageId = resolvePackageBinding(sessionId);

        String safeRequestId = safeCorrelationId(requestId);
        safetyPolicy.assess(normalizedAnswer).ifPresent(finding -> {
            safetyAudits.recordInput(
                    userId, sessionId, safeRequestId, "interview_answer", normalizedAnswer, finding);
            if (finding.blocked()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "unsafe_interview_answer", "回答包含不安全指令，已停止处理");
            }
        });

        int turnChanged = jdbc.update("""
                UPDATE turns
                SET answer_text = ?, answer_idempotency_key = ?, status = 'processing', answered_at = now()
                WHERE session_id = ? AND turn_index = ? AND status = 'waiting_answer'
                  AND EXISTS (
                      SELECT 1 FROM sessions s
                      WHERE s.id = ? AND s.status = 'active' AND s.expires_at > now())
                """, normalizedAnswer, idempotencyKey, sessionId, session.currentTurnIndex(), sessionId);
        if (turnChanged != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "interview_turn_not_ready", "当前轮次不能提交回答");
        }
        int sessionChanged = jdbc.update("""
                UPDATE sessions
                SET status = 'awaiting_ai', version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'active' AND expires_at > now()
                """, sessionId);
        if (sessionChanged != 1) {
            throw sessionExpired();
        }

        UUID jobId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            Map<String, Object> jobInput = new LinkedHashMap<>();
            jobInput.put("session_id", sessionId.toString());
            jobInput.put("turn_index", session.currentTurnIndex());
            String inputRef = mapper.writeValueAsString(jobInput);
            jdbc.update("""
                    INSERT INTO ai_jobs(
                        id, owner_id, session_id, package_id, kind, status, stage, progress,
                        idempotency_key, request_hash, input_ref, expires_at)
                    VALUES (?, ?, ?, ?, 'GENERATE_FOLLOW_UP', 'QUEUED', 'WAITING_FOR_WORKER', 0,
                            ?, ?, ?::jsonb, ?)
                    """, jobId, userId, sessionId, packageId,
                    idempotencyKey, requestHash, inputRef,
                    java.sql.Timestamp.from(now.plus(30, ChronoUnit.MINUTES)));
            insertOutbox(jobId, 0, safeRequestId, now);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize AI task", exception);
        }
        TaskView task = tasks.findByOwnerAndIdempotency(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Created AI task was not found"));
        return new AnswerAcceptance(get(userId, sessionId), task);
    }

    private UUID resolvePackageBinding(UUID sessionId) {
        List<UUID> packageIds = jdbc.query("""
                SELECT package_id
                FROM interview_package_stages
                WHERE session_id = ?
                ORDER BY package_id
                """, (resultSet, rowNumber) -> resultSet.getObject("package_id", UUID.class), sessionId);
        if (packageIds.size() > 1) {
            throw new IllegalStateException("Interview session is bound to multiple interview packages");
        }
        return packageIds.isEmpty() ? null : packageIds.getFirst();
    }

    private InterviewView mapView(ResultSet resultSet, int rowNumber) throws SQLException {
        String reportJson = resultSet.getString("report_json");
        Object report = null;
        if (reportJson != null) {
            try {
                report = mapper.readTree(reportJson);
            } catch (JacksonException exception) {
                throw new SQLException("Invalid reports.report_json JSON", exception);
            }
        }
        int turnIndex = resultSet.getInt("turn_index");
        InterviewView.Question question = resultSet.wasNull()
                ? null
                : new InterviewView.Question(
                        turnIndex, resultSet.getString("round_name"), resultSet.getString("question_text"));
        java.sql.Timestamp expiresTs = resultSet.getTimestamp("expires_at");
        return new InterviewView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("interview_type"),
                resultSet.getString("status"),
                resultSet.getInt("current_turn_index"),
                resultSet.getInt("total_turns"),
                question,
                null,
                report,
                resultSet.getTimestamp("updated_at").toInstant(),
                expiresTs == null ? null : expiresTs.toInstant());
    }

    private void insertOutbox(UUID jobId, long version, String requestId, Instant now)
            throws JacksonException {
        AiJobEnvelope envelope = AiJobEnvelope.create(jobId, requestId, requestId, now);
        String payload = mapper.writeValueAsString(envelope);
        jdbc.update("""
                INSERT INTO outbox_events(
                    aggregate_type, aggregate_id, aggregate_version, event_type,
                    payload, correlation_id, trace_id)
                VALUES ('AI_JOB', ?, ?, 'AI_JOB_QUEUED', ?::jsonb, ?, ?)
                """, jobId, version, payload, requestId, requestId);
    }

    private void cancelExpiredOpenSessions(UUID userId) {
        jdbc.update("""
                UPDATE ai_jobs
                SET status = 'CANCELLED', stage = 'SESSION_EXPIRED', retryable = false,
                    lease_owner = NULL, lease_until = NULL, version = version + 1, updated_at = now()
                WHERE session_id IN (
                    SELECT id FROM sessions
                    WHERE user_id = ? AND status IN ('created', 'active', 'awaiting_ai')
                      AND expires_at <= now())
                  AND status IN ('QUEUED', 'RUNNING', 'RETRYING')
                """, userId);
        jdbc.update("""
                UPDATE sessions
                SET status = 'cancelled', failure_code = 'SESSION_EXPIRED', ended_at = now(),
                    version = version + 1, updated_at = now()
                WHERE user_id = ? AND status IN ('created', 'active', 'awaiting_ai')
                  AND expires_at <= now()
                """, userId);
        jdbc.update("""
                UPDATE turns SET status = 'cancelled'
                WHERE session_id IN (
                    SELECT id FROM sessions
                    WHERE user_id = ? AND status = 'cancelled'
                      AND failure_code = 'SESSION_EXPIRED')
                  AND status IN ('waiting_answer', 'processing')
                """, userId);
    }

    private Optional<InterviewView> replayStart(
            UUID userId,
            UUID requestedSessionId,
            InterviewType requestedType,
            String idempotencyKey) {
        Optional<ExistingStart> existing = jdbc.query("""
                SELECT id, interview_type
                FROM sessions
                WHERE user_id = ? AND start_idempotency_key = ?
                """, (rs, row) -> new ExistingStart(
                rs.getObject("id", UUID.class),
                rs.getString("interview_type")), userId, idempotencyKey)
                .stream().findFirst();
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ExistingStart value = existing.get();
        if (!value.id().equals(requestedSessionId)
                || !value.interviewType().equals(dbType(requestedType))) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                    "同一幂等键不能用于不同的面试请求");
        }
        return Optional.of(get(userId, value.id()));
    }

    private Optional<AnswerAcceptance> replayAnswer(
            UUID userId,
            UUID requestedSessionId,
            int requestedTurnIndex,
            String idempotencyKey,
            String requestHash) {
        Optional<ExistingJob> existing = jdbc.query("""
                SELECT j.id, j.session_id, j.request_hash,
                       (j.input_ref ->> 'turn_index')::integer AS turn_index
                FROM ai_jobs j
                JOIN sessions s ON s.id = j.session_id
                -- 过期会话不得通过幂等重放绕过 answer 的有效期约束。
                WHERE j.owner_id = ? AND j.idempotency_key = ? AND s.expires_at > now()
                """, (rs, row) -> new ExistingJob(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("request_hash"),
                rs.getInt("turn_index")), userId, idempotencyKey).stream().findFirst();
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ExistingJob job = existing.get();
        if (!requestedSessionId.equals(job.sessionId())
                || requestedTurnIndex != job.turnIndex()
                || !requestHash.equals(job.requestHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                    "同一幂等键不能用于不同的回答");
        }
        TaskView task = tasks.get(userId, false, job.id());
        return Optional.of(new AnswerAcceptance(get(userId, requestedSessionId), task));
    }

    private static InterviewType parseType(String raw) {
        try {
            return InterviewType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "unsupported_interview_type", "不支持的面试类型");
        }
    }

    private static String dbType(InterviewType type) {
        return type.name().toLowerCase(Locale.ROOT);
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

    private static String safeCorrelationId(String requestId) {
        String value = requestId == null || requestId.isBlank() ? "req_unknown" : requestId.trim();
        return value.length() <= 96 ? value : value.substring(0, 96);
    }

    static String normalizeAnswerText(String answerText) {
        String normalized = answerText == null ? "" : answerText.trim();
        boolean unsupportedControl = normalized.codePoints().anyMatch(character ->
                Character.isISOControl(character)
                        && character != '\n'
                        && character != '\r'
                        && character != '\t');
        if (unsupportedControl) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_failed", "回答包含不支持的控制字符");
        }
        return normalized;
    }

    private static ApiException sessionExpired() {
        return new ApiException(
                HttpStatus.GONE, "interview_session_expired", "面试训练已过期，请重新开始");
    }

    private static String normalizeOpeningQuestion(String question) {
        String normalized = question == null ? "" : question
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (normalized.length() < 8 || normalized.length() > 800) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "personalized_question_invalid", "个性化首题格式不正确");
        }
        return normalized;
    }

    private record InterviewPlan(int totalTurns, int creditCost, String firstRound, String firstQuestion) {
    }

    private record ExistingStart(UUID id, String interviewType) {
    }

    private record UserBilling(int balance, String role) {
    }

    private record Voucher(UUID id, int remainingUses) {
    }

    private record ExistingJob(UUID id, UUID sessionId, String requestHash, int turnIndex) {
    }

    private record SessionForAnswer(
            String status,
            int currentTurnIndex,
            int totalTurns,
            boolean unexpired) {
    }

    private record SpeechState(
            String interviewType,
            String sessionStatus,
            int turnIndex,
            String turnStatus,
            String questionText,
            boolean unexpired) {
    }
}
