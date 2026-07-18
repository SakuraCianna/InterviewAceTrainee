package icu.sakuracianna.mianba.aiwork.worker;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import icu.sakuracianna.mianba.aiwork.messaging.AiJobEnvelope;
import icu.sakuracianna.mianba.aiwork.messaging.AiMessagingTopology;
import icu.sakuracianna.mianba.interview.safety.AiOutputSafetyPolicy;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyAuditService;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 消费 AI 任务并把模型结果原子写回业务表。
 *
 * RabbitMQ 消息只携带任务标识。Worker 先在数据库中取得有时限的租约，再读取回答正文。
 * 结果事务提交后才确认消息，避免数据库提交失败时因提前确认而永久丢失任务；重复投递则由
 * 消息 ID、任务状态和版本共同消除副作用。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "worker")
public class AiJobWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiJobWorker.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(3);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final InterviewAiGenerator generator;
    private final ContentSafetyAuditService safetyAudits;
    private final Clock clock;
    private final String workerId = "worker-" + UUID.randomUUID();

    @Autowired
    public AiJobWorker(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            InterviewAiGenerator generator,
            ContentSafetyAuditService safetyAudits,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.generator = generator;
        this.safetyAudits = safetyAudits;
        this.clock = clock;
    }

    AiJobWorker(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            InterviewAiGenerator generator,
            Clock clock) {
        this(jdbc, transactions, mapper, generator, null, clock);
    }

    /**
     * 手动确认消息，确保数据库状态和 RabbitMQ 确认之间的顺序可控。
     *
     * @param message RabbitMQ 持久化消息
     * @param channel 当前消费者通道
     * @throws IOException 通道确认失败时由容器处理连接恢复
     */
    @RabbitListener(queues = AiMessagingTopology.JOB_QUEUE)
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        AiJobEnvelope envelope;
        try {
            envelope = mapper.readValue(message.getBody(), AiJobEnvelope.class);
        } catch (RuntimeException exception) {
            LOGGER.warn("Rejecting malformed AI job message message_id={}",
                    message.getMessageProperties().getMessageId());
            channel.basicReject(deliveryTag, false);
            return;
        }

        JobClaim claim = null;
        ProviderConfig provider = null;
        Instant callStartedAt = null;
        AiCallObservation callObservation = null;
        try {
            Boolean duplicate = transactions.execute(status -> alreadyProcessed(envelope.messageId()));
            if (Boolean.TRUE.equals(duplicate)) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            claim = transactions.execute(status -> claim(envelope.jobId()));
            if (claim == null) {
                // 迟到消息、重复消息或已取消任务均没有可再次执行的副作用。
                channel.basicAck(deliveryTag, false);
                return;
            }

            provider = loadInterviewProvider();
            if (!provider.enabled()) {
                throw new AiWorkerException(
                        "AI_PROVIDER_DISABLED", "面试模型供应商已由管理员停用", true);
            }
            JobClaim claimedJob = claim;
            // 外呼前用短事务重新读取内容与状态；校验失败时回答正文不会发送给 Provider。
            InterviewAiGenerator.InterviewAiInput input = transactions.execute(
                    status -> loadInput(claimedJob));
            if (input == null) {
                throw new IllegalStateException("Provider-call state guard returned no result");
            }
            callStartedAt = clock.instant();
            InterviewEvaluation generatedEvaluation = generator.evaluate(input);
            AiOutputSafetyPolicy.assess(generatedEvaluation).ifPresent(finding -> {
                if (safetyAudits != null) {
                    safetyAudits.recordOutput(
                            claimedJob.sessionId(), claimedJob.id(), "ai_interview_output",
                            AiOutputSafetyPolicy.auditText(generatedEvaluation), finding);
                }
            });
            InterviewEvaluation evaluation = AiOutputSafetyPolicy.sanitize(generatedEvaluation);
            callObservation = AiCallObservation.success(
                    provider, elapsedMillis(callStartedAt, clock.instant()));
            JobClaim completedClaim = claim;
            AiCallObservation completedCall = callObservation;
            // 先原子提交轮次/报告/任务/去重记录，再 ACK；反过来会在数据库提交失败时永久丢任务。
            transactions.executeWithoutResult(
                    status -> complete(envelope, completedClaim, input, evaluation, completedCall));
            channel.basicAck(deliveryTag, false);
        } catch (AiWorkerException exception) {
            if (claim == null) {
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            try {
                JobClaim failedClaim = claim;
                AiCallObservation failedCall = callObservation;
                if (failedCall == null && provider != null && callStartedAt != null) {
                    failedCall = AiCallObservation.failure(
                            provider, elapsedMillis(callStartedAt, clock.instant()), exception.code());
                }
                AiCallObservation recordedCall = failedCall;
                transactions.execute(status -> fail(envelope, failedClaim, exception, recordedCall));
                // 业务终态以已提交的 ai_jobs 为权威告警并由管理端展示；提交后统一 ACK。
                // DLQ 只承接畸形消息和超过 delivery-limit 的基础设施毒消息。
                channel.basicAck(deliveryTag, false);
            } catch (RuntimeException persistenceException) {
                // 失败状态未可靠提交时必须显式重新入队，不能交给容器的拒绝默认值进入 DLQ。
                requeueInfrastructureFailure(envelope, persistenceException, channel, deliveryTag);
            }
        } catch (RuntimeException exception) {
            requeueInfrastructureFailure(envelope, exception, channel, deliveryTag);
        }
    }

    private void requeueInfrastructureFailure(
            AiJobEnvelope envelope,
            RuntimeException exception,
            Channel channel,
            long deliveryTag) throws IOException {
        FailureDiagnostic diagnostic = diagnoseFailure(exception);
        LOGGER.error(
                "AI worker infrastructure failure job_id={} error_type={} sql_state={} worker_location={}",
                envelope.jobId(), diagnostic.errorType(), diagnostic.sqlState(),
                diagnostic.workerLocation());
        // 数据库或进程级异常未形成可靠状态，交还队列并由 quorum delivery-limit 兜底。
        channel.basicNack(deliveryTag, false, true);
    }

    /** 只提取稳定的数据库分类和本站代码位置，不记录 SQL、参数、异常消息或用户内容。 */
    static FailureDiagnostic diagnoseFailure(Throwable failure) {
        String errorType = failure == null ? "Unknown" : failure.getClass().getSimpleName();
        String sqlState = "none";
        String workerLocation = "unknown";
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if ("none".equals(sqlState) && current instanceof SQLException sqlException) {
                String candidate = sqlException.getSQLState();
                if (candidate != null && candidate.matches("[0-9A-Z]{5}")) {
                    sqlState = candidate;
                }
            }
            if ("unknown".equals(workerLocation)) {
                for (StackTraceElement frame : current.getStackTrace()) {
                    if (AiJobWorker.class.getName().equals(frame.getClassName())) {
                        int line = Math.max(frame.getLineNumber(), 0);
                        workerLocation = frame.getMethodName() + ":" + line;
                        break;
                    }
                }
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return new FailureDiagnostic(errorType, sqlState, workerLocation);
    }

    private boolean alreadyProcessed(UUID messageId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE consumer_name = ? AND message_id = ?",
                Long.class, "ai-job-worker-v1", messageId);
        return count != null && count > 0;
    }

    private JobClaim claim(UUID jobId) {
        Instant now = clock.instant();
        List<UUID> expiredSessions = jdbc.query("""
                UPDATE ai_jobs
                SET status = 'CANCELLED', stage = 'TASK_EXPIRED', retryable = false,
                    error_code = 'TASK_EXPIRED', error_message = '任务已过期',
                    lease_owner = NULL, lease_until = NULL,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RETRYING') AND expires_at <= ?
                RETURNING session_id
                """, (resultSet, rowNumber) -> resultSet.getObject("session_id", UUID.class),
                jobId, Timestamp.from(now));
        expiredSessions.stream().filter(java.util.Objects::nonNull).forEach(this::restoreInterviewContext);

        List<UUID> exhaustedSessions = jdbc.query("""
                UPDATE ai_jobs
                SET status = 'FAILED', stage = 'NEEDS_ATTENTION',
                    retryable = (manual_retry_count < 1),
                    error_code = 'ATTEMPT_BUDGET_EXHAUSTED', error_message = '任务自动重试次数已用尽',
                    lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'QUEUED' AND attempt >= max_attempts
                RETURNING session_id
                """, (resultSet, rowNumber) -> resultSet.getObject("session_id", UUID.class), jobId);
        exhaustedSessions.stream().filter(java.util.Objects::nonNull).forEach(this::restoreInterviewContext);

        return jdbc.query("""
                UPDATE ai_jobs
                SET status = 'RUNNING', stage = 'CALLING_MODEL', progress = 35,
                    attempt = attempt + 1, lease_owner = ?, lease_until = ?,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'QUEUED' AND attempt < max_attempts AND expires_at > ?
                RETURNING id, session_id, attempt, max_attempts, version, expires_at
                """, (rs, row) -> new JobClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getLong("version"),
                rs.getTimestamp("expires_at").toInstant()),
                workerId, Timestamp.from(now.plus(LEASE_DURATION)), jobId, Timestamp.from(now))
                .stream().findFirst().orElse(null);
    }

    private InterviewAiGenerator.InterviewAiInput loadInput(JobClaim claim) {
        Instant now = clock.instant();
        if (!now.isBefore(claim.expiresAt())) {
            throw new AiWorkerException("TASK_EXPIRED", "任务已过期", false);
        }
        InterviewAiGenerator.InterviewAiInput input = jdbc.query("""
                SELECT s.interview_type, s.current_turn_index, s.total_turns,
                       t.round_name, t.question_text, t.answer_text
                FROM ai_jobs j
                JOIN sessions s ON s.id = j.session_id
                JOIN turns t ON t.session_id = s.id AND t.turn_index = s.current_turn_index
                WHERE j.id = ? AND j.status = 'RUNNING' AND j.lease_owner = ?
                  AND j.version = ? AND j.lease_until > ? AND j.expires_at > ?
                  AND s.status = 'awaiting_ai' AND s.expires_at > ?
                  AND t.status = 'processing'
                """, (rs, row) -> new InterviewAiGenerator.InterviewAiInput(
                rs.getString("interview_type"),
                rs.getString("round_name"),
                rs.getString("question_text"),
                rs.getString("answer_text"),
                rs.getInt("current_turn_index"),
                rs.getInt("total_turns"),
                Map.of()), claim.id(), workerId, claim.version(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)).stream().findFirst()
                .orElseThrow(() -> new AiWorkerException(
                        "TASK_STALE", "任务对应的面试轮次已变化或已过期", false));
        List<String> previousQuestions = jdbc.query("""
                SELECT question_text
                FROM turns
                WHERE session_id = ? AND turn_index < ?
                ORDER BY turn_index
                LIMIT 12
                """, (resultSet, rowNumber) -> resultSet.getString("question_text"),
                claim.sessionId(), input.turnIndex());
        return input.withPreviousQuestions(previousQuestions);
    }

    private ProviderConfig loadInterviewProvider() {
        return jdbc.query("""
                SELECT id, provider_type, provider_name, model_name, purpose, enabled
                FROM providers
                WHERE provider_type = 'llm' AND provider_name = 'deepseek' AND purpose = 'interview'
                ORDER BY priority DESC
                LIMIT 1
                """, (resultSet, rowNumber) -> new ProviderConfig(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("provider_type"),
                resultSet.getString("provider_name"),
                resultSet.getString("model_name"),
                resultSet.getString("purpose"),
                resultSet.getBoolean("enabled"))).stream().findFirst()
                .orElseThrow(() -> new AiWorkerException(
                        "AI_PROVIDER_UNAVAILABLE", "面试模型供应商配置不存在", true));
    }

    private void complete(
            AiJobEnvelope envelope,
            JobClaim claim,
            InterviewAiGenerator.InterviewAiInput input,
            InterviewEvaluation evaluation,
            AiCallObservation callObservation) {
        List<UUID> lockedJobs = jdbc.query("""
                SELECT id FROM ai_jobs
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND version = ?
                  AND lease_until > ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                claim.id(), workerId, claim.version(), Timestamp.from(clock.instant()));
        if (lockedJobs.isEmpty()) {
            throw new IllegalStateException("AI job lease was lost before completion started");
        }
        // Provider 调用已产生费用和外部观测，即使结果因过期被丢弃也必须记录。
        recordAiCall(claim, callObservation);
        if (!clock.instant().isBefore(claim.expiresAt())) {
            cancelExpiredJob(claim);
            restoreInterviewContext(claim.sessionId());
            markProcessed(envelope, claim.id());
            return;
        }
        // 所有跨任务与会话的事务统一按 job -> session -> turn 加锁，避免和恢复/删除流程形成死锁。
        // Provider 请求一旦发出无法撤回；若期间状态或有效期变化，本事务必须拒绝写回并取消任务。
        SessionState session = jdbc.query("""
                SELECT status, current_turn_index, total_turns, expires_at
                FROM sessions WHERE id = ? FOR UPDATE
                """, (rs, row) -> new SessionState(
                rs.getString("status"), rs.getInt("current_turn_index"), rs.getInt("total_turns"),
                rs.getTimestamp("expires_at").toInstant()),
                claim.sessionId()).stream().findFirst()
                .orElseThrow(() -> new AiWorkerException("TASK_STALE", "面试会话已删除", false));
        if (!"awaiting_ai".equals(session.status())
                || session.currentTurnIndex() != input.turnIndex()
                || !clock.instant().isBefore(session.expiresAt())) {
            cancelStaleJob(claim, "任务对应状态已变化或面试会话已过期");
            markProcessed(envelope, claim.id());
            return;
        }

        int turnChanged = jdbc.update("""
                UPDATE turns
                SET status = 'answered', evaluation_score = ?, evaluation_feedback = ?, evaluated_at = now()
                WHERE session_id = ? AND turn_index = ? AND status = 'processing'
                """, evaluation.score(), evaluation.feedback(), claim.sessionId(), input.turnIndex());
        if (turnChanged != 1) {
            throw new IllegalStateException("Interview turn changed before evaluation was stored");
        }

        boolean finalTurn = input.finalTurn();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", claim.sessionId().toString());
        result.put("turn_index", input.turnIndex());
        result.put("score", evaluation.score());
        result.put("feedback", evaluation.feedback());

        if (finalTurn) {
            ReportResult report = buildReport(claim.sessionId(), input);
            String reportJson = writeJson(report.body());
            jdbc.update("""
                    INSERT INTO reports(session_id, total_score, report_json)
                    VALUES (?, ?, ?::jsonb)
                    ON CONFLICT (session_id) DO UPDATE
                    SET total_score = EXCLUDED.total_score,
                        report_json = EXCLUDED.report_json,
                        updated_at = now()
                    """, claim.sessionId(), report.totalScore(), reportJson);
            int sessionChanged = jdbc.update("""
                    UPDATE sessions
                    SET status = 'completed', ended_at = now(), version = version + 1, updated_at = now()
                    WHERE id = ? AND status = 'awaiting_ai' AND expires_at > now()
                    """, claim.sessionId());
            if (sessionChanged != 1) {
                throw new IllegalStateException("Interview session expired before completion was stored");
            }
            result.put("status", "completed");
            result.put("report_ready", true);
        } else {
            int nextIndex = input.turnIndex() + 1;
            jdbc.update("""
                    INSERT INTO turns(session_id, turn_index, round_name, question_text)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (session_id, turn_index) DO NOTHING
                    """, claim.sessionId(), nextIndex, evaluation.roundName(), evaluation.nextQuestion());
            int sessionChanged = jdbc.update("""
                    UPDATE sessions
                    SET status = 'active', current_turn_index = ?,
                        version = version + 1, updated_at = now()
                    WHERE id = ? AND status = 'awaiting_ai' AND expires_at > now()
                    """, nextIndex, claim.sessionId());
            if (sessionChanged != 1) {
                throw new IllegalStateException("Interview session expired before next turn was stored");
            }
            result.put("status", "active");
            result.put("next_turn_index", nextIndex);
        }

        int changed = jdbc.update("""
                UPDATE ai_jobs
                SET status = 'SUCCEEDED', stage = 'COMPLETED', progress = 100,
                    result_ref = ?::jsonb, error_code = NULL, error_message = NULL,
                    retryable = false, lease_owner = NULL, lease_until = NULL,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND version = ?
                  AND lease_until > now() AND expires_at > now()
                """, writeJson(result), claim.id(), workerId, claim.version());
        if (changed != 1) {
            throw new IllegalStateException("AI job lease was lost before completion");
        }
        markProcessed(envelope, claim.id());
    }

    private FailureDisposition fail(
            AiJobEnvelope envelope,
            JobClaim claim,
            AiWorkerException failure,
            AiCallObservation callObservation) {
        Instant now = clock.instant();
        boolean taskStale = "TASK_STALE".equals(failure.code());
        boolean taskExpired = "TASK_EXPIRED".equals(failure.code())
                || !now.isBefore(claim.expiresAt());
        boolean canRetry = !taskStale && !taskExpired
                && failure.retryable()
                && claim.attempt() < claim.maxAttempts()
                && now.isBefore(claim.expiresAt());
        long nextVersion = claim.version() + 1;
        if (canRetry) {
            Duration delay = retryDelay(claim.attempt());
            Instant availableAt = now.plus(delay);
            int changed = jdbc.update("""
                    UPDATE ai_jobs
                    SET status = 'RETRYING', stage = 'RETRY_DELAY', progress = 0,
                        error_code = ?, error_message = ?, retryable = true,
                        lease_owner = NULL, lease_until = NULL, next_attempt_at = ?,
                        version = ?, updated_at = now()
                    WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND version = ?
                    """, failure.code(), safeErrorMessage(failure), Timestamp.from(availableAt),
                    nextVersion, claim.id(), workerId, claim.version());
            if (changed != 1) {
                throw new IllegalStateException("AI job lease was lost while recording retry");
            }
            recordAiCall(claim, callObservation);
            insertRetryOutbox(envelope, claim.id(), nextVersion, availableAt);
            markProcessed(envelope, claim.id());
            return FailureDisposition.RETRY_SCHEDULED;
        }

        boolean cancelled = taskExpired || taskStale;
        String status = cancelled ? "CANCELLED" : "FAILED";
        String stage = taskExpired ? "TASK_EXPIRED" : taskStale ? "TASK_STALE" : "NEEDS_ATTENTION";
        String errorCode = taskExpired ? "TASK_EXPIRED" : taskStale ? "TASK_STALE" : failure.code();
        boolean manualRetryAllowed = !cancelled && failure.retryable();
        int changed = jdbc.update("""
                UPDATE ai_jobs
                SET status = ?, stage = ?, progress = 0,
                    error_code = ?, error_message = ?,
                    retryable = (? AND manual_retry_count < 1),
                    lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL,
                    version = ?, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND version = ?
                """, status, stage, errorCode,
                taskExpired ? "任务已过期" : safeErrorMessage(failure), manualRetryAllowed, nextVersion,
                claim.id(), workerId, claim.version());
        if (changed != 1) {
            throw new IllegalStateException("AI job lease was lost while recording terminal failure");
        }
        recordAiCall(claim, callObservation);
        if (!taskStale) {
            restoreInterviewContext(claim.sessionId());
        }
        markProcessed(envelope, claim.id());
        return FailureDisposition.TERMINAL;
    }

    private void cancelStaleJob(JobClaim claim, String message) {
        int changed = jdbc.update("""
                UPDATE ai_jobs
                SET status = 'CANCELLED', stage = 'TASK_STALE', retryable = false,
                    error_code = 'TASK_STALE', error_message = ?,
                    lease_owner = NULL, lease_until = NULL,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND version = ?
                """, message, claim.id(), workerId, claim.version());
        if (changed != 1) {
            throw new IllegalStateException("AI job lease was lost while cancelling stale work");
        }
    }

    private void cancelExpiredJob(JobClaim claim) {
        int changed = jdbc.update("""
                UPDATE ai_jobs
                SET status = 'CANCELLED', stage = 'TASK_EXPIRED', retryable = false,
                    error_code = 'TASK_EXPIRED', error_message = '任务已过期',
                    lease_owner = NULL, lease_until = NULL,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND version = ?
                """, claim.id(), workerId, claim.version());
        if (changed != 1) {
            throw new IllegalStateException("AI job lease was lost while cancelling expired work");
        }
    }

    private void restoreInterviewContext(UUID sessionId) {
        int sessionChanged = jdbc.update("""
                UPDATE sessions SET status = 'active', version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'awaiting_ai' AND expires_at > now()
                """, sessionId);
        if (sessionChanged == 1) {
            // 只有会话真正恢复后才恢复轮次，避免删除或过期会话重新暴露回答入口。
            jdbc.update("""
                    UPDATE turns SET status = 'waiting_answer'
                    WHERE session_id = ? AND status = 'processing'
                    """, sessionId);
        }
    }

    private void markProcessed(AiJobEnvelope envelope, UUID jobId) {
        jdbc.update("""
                INSERT INTO processed_messages(consumer_name, message_id, job_id)
                VALUES ('ai-job-worker-v1', ?, ?)
                ON CONFLICT DO NOTHING
                """, envelope.messageId(), jobId);
    }

    private void recordAiCall(JobClaim claim, AiCallObservation observation) {
        if (observation == null) {
            return;
        }
        ProviderConfig provider = observation.provider();
        // 调用日志只保留运营元数据，绝不保存提示词、回答正文或供应商凭据。
        jdbc.update("""
                INSERT INTO ai_call_logs(
                    job_id, session_id, provider_id, provider_type, provider_name,
                    model_name, purpose, success, latency_ms, error_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, claim.id(), claim.sessionId(), provider.id(), provider.type(), provider.name(),
                provider.model(), provider.purpose(), observation.success(), observation.latencyMs(),
                observation.errorCode());
    }

    private void insertRetryOutbox(AiJobEnvelope original, UUID jobId, long version, Instant availableAt) {
        AiJobEnvelope retry = AiJobEnvelope.create(
                jobId, original.correlationId(), original.traceId(), clock.instant());
        jdbc.update("""
                INSERT INTO outbox_events(
                    aggregate_type, aggregate_id, aggregate_version, event_type,
                    payload, correlation_id, trace_id, available_at)
                VALUES ('AI_JOB', ?, ?, 'AI_JOB_QUEUED', ?::jsonb, ?, ?, ?)
                """, jobId, version, writeJson(retry), retry.correlationId(), retry.traceId(),
                Timestamp.from(availableAt));
    }

    private ReportResult buildReport(
            UUID sessionId,
            InterviewAiGenerator.InterviewAiInput input) {
        List<TurnEvaluationRow> evaluatedTurns = jdbc.query("""
                SELECT turn_index, round_name, question_text, answer_text,
                       evaluation_score, evaluation_feedback
                FROM turns WHERE session_id = ? ORDER BY turn_index
                """, (rs, row) -> new TurnEvaluationRow(
                rs.getInt("turn_index"),
                rs.getString("round_name"),
                rs.getString("question_text"),
                rs.getString("answer_text"),
                requiredScore(rs),
                rs.getString("evaluation_feedback")), sessionId);
        List<InterviewAiGenerator.ReportTurn> reportTurns = evaluatedTurns.stream()
                .map(t -> new InterviewAiGenerator.ReportTurn(
                        t.turnIndex(), t.roundName(), t.question(), t.answer(),
                        t.score(), t.feedback()))
                .toList();
        String aiSummary = generator.synthesizeReportSummary(reportTurns, input.interviewType());
        return assembleReport(sessionId, input, evaluatedTurns, aiSummary);
    }

    /**
     * 组装最终报告的纯函数入口，保证 Worker 写库与回归测试使用同一份双语文案策略。
     *
     * @param sessionId 面试会话标识
     * @param input 可信业务类型与当前轮次上下文
     * @param evaluatedTurns 已完成的轮次评价
     * @return 可直接持久化的最终报告
     */
    static ReportResult assembleReport(
            UUID sessionId,
            InterviewAiGenerator.InterviewAiInput input,
            List<TurnEvaluationRow> evaluatedTurns) {
        return assembleReport(sessionId, input, evaluatedTurns, null);
    }

    static ReportResult assembleReport(
            UUID sessionId,
            InterviewAiGenerator.InterviewAiInput input,
            List<TurnEvaluationRow> evaluatedTurns,
            String aiSummary) {
        ReportCopyPolicy copy = ReportCopyPolicy.forInterviewType(input.interviewType());
        ReportAggregation aggregation = aggregateEvaluations(input.interviewType(), evaluatedTurns);

        List<Map<String, Object>> turns = new ArrayList<>();
        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (TurnEvaluationRow row : evaluatedTurns) {
            String roundName = copy.roundName(row);
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("turn_index", row.turnIndex());
            turn.put("round_name", roundName);
            turn.put("question", row.question());
            turn.put("answer", row.answer());
            turn.put("score", row.score());
            turn.put("feedback", row.feedback());
            turns.add(turn);

            Map<String, Object> dimension = new LinkedHashMap<>();
            dimension.put("name", copy.dimensionName(row, roundName));
            dimension.put("score", row.score());
            dimension.put("comment", row.feedback());
            dimensions.add(dimension);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("session_id", sessionId.toString());
        report.put("interview_type", input.interviewType());
        report.put("total_score", aggregation.totalScore());
        report.put("readiness_level", copy.readinessLevel(aggregation.totalScore()));
        report.put("score_explanation", copy.scoreExplanation(evaluatedTurns.size()));
        report.put("summary", aiSummary != null && !aiSummary.isBlank()
                ? aiSummary : aggregation.summary());
        report.put("dimensions", dimensions);
        report.put("strengths", aggregation.strengths());
        report.put("improvements", aggregation.improvements());
        report.put("next_plan", aggregation.nextPlan());
        report.put("priority_actions", aggregation.improvements().stream().limit(1).toList());
        report.put("evidence", aggregation.evidence());
        report.put("risk_flags", List.of());
        report.put("recommended_drills", aggregation.recommendedDrills());
        report.put("public_knowledge_applied", !input.publicKnowledgeContext().isEmpty());
        report.put("turns", turns);
        return new ReportResult(aggregation.totalScore(), report);
    }

    static ReportAggregation aggregateEvaluations(
            String interviewType,
            List<TurnEvaluationRow> turns) {
        if (turns == null || turns.isEmpty()) {
            throw new IllegalArgumentException("At least one evaluated turn is required");
        }
        ReportCopyPolicy copy = ReportCopyPolicy.forInterviewType(interviewType);
        int totalScore = (int) Math.round(turns.stream()
                .mapToInt(TurnEvaluationRow::score)
                .average()
                .orElseThrow());
        List<TurnEvaluationRow> ranked = new ArrayList<>(turns);
        ranked.sort(java.util.Comparator.comparingInt(TurnEvaluationRow::score).reversed());
        List<String> strengths = ranked.stream().limit(Math.min(2, ranked.size()))
                .map(copy::evaluationSummary).toList();
        List<TurnEvaluationRow> weakestFirst = new ArrayList<>(turns);
        weakestFirst.sort(java.util.Comparator.comparingInt(TurnEvaluationRow::score));
        List<String> improvements = weakestFirst.stream().limit(Math.min(2, weakestFirst.size()))
                .map(copy::evaluationSummary).toList();
        List<String> evidence = turns.stream()
                .map(copy::evidence)
                .toList();
        String summary = copy.summary(turns.size(), totalScore, weakestFirst.getFirst().feedback());
        List<String> nextPlan = copy.nextPlan();
        List<String> drills = copy.recommendedDrills(totalScore);
        return new ReportAggregation(
                totalScore, summary, strengths, improvements, evidence, nextPlan, drills);
    }

    private static int requiredScore(ResultSet resultSet) throws SQLException {
        Number score = (Number) resultSet.getObject("evaluation_score");
        if (score == null) {
            throw new SQLException("Completed turn has no evaluation score");
        }
        return score.intValue();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize worker result", exception);
        }
    }

    private static Duration retryDelay(int attempt) {
        long[] delays = {5, 20, 60};
        return Duration.ofSeconds(delays[Math.min(Math.max(attempt - 1, 0), delays.length - 1)]);
    }

    private static String safeErrorMessage(Throwable failure) {
        String value = failure.getMessage() == null ? "任务处理失败" : failure.getMessage();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static int elapsedMillis(Instant startedAt, Instant finishedAt) {
        if (startedAt == null) {
            return 0;
        }
        long millis = Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private enum FailureDisposition {
        RETRY_SCHEDULED,
        TERMINAL
    }

    record FailureDiagnostic(String errorType, String sqlState, String workerLocation) {
    }

    private record JobClaim(
            UUID id,
            UUID sessionId,
            int attempt,
            int maxAttempts,
            long version,
            Instant expiresAt) {
    }

    private record SessionState(
            String status,
            int currentTurnIndex,
            int totalTurns,
            Instant expiresAt) {
    }

    private record ProviderConfig(
            UUID id,
            String type,
            String name,
            String model,
            String purpose,
            boolean enabled) {
    }

    private record AiCallObservation(
            ProviderConfig provider,
            boolean success,
            int latencyMs,
            String errorCode) {
        private static AiCallObservation success(ProviderConfig provider, int latencyMs) {
            return new AiCallObservation(provider, true, latencyMs, null);
        }

        private static AiCallObservation failure(
                ProviderConfig provider, int latencyMs, String errorCode) {
            return new AiCallObservation(provider, false, latencyMs, errorCode);
        }
    }

    record TurnEvaluationRow(
            int turnIndex,
            String roundName,
            String question,
            String answer,
            int score,
            String feedback) {
    }

    record ReportAggregation(
            int totalScore,
            String summary,
            List<String> strengths,
            List<String> improvements,
            List<String> evidence,
            List<String> nextPlan,
            List<String> recommendedDrills) {
    }

    record ReportResult(int totalScore, Map<String, Object> body) {
    }

    /**
     * 最终报告的服务端文案策略。
     *
     * IELTS 标签只取可信阶段表，防止历史脏数据或模型漂移把中文轮次名带回英文报告。
     */
    private record ReportCopyPolicy(boolean english, List<String> trustedStages) {
        private static ReportCopyPolicy forInterviewType(String interviewType) {
            InterviewPromptPolicy.Profile profile = InterviewPromptPolicy.profile(interviewType);
            return new ReportCopyPolicy(profile.englishOnly(), profile.stages());
        }

        private String roundName(TurnEvaluationRow row) {
            if (!english) {
                return row.roundName();
            }
            int index = Math.min(Math.max(row.turnIndex(), 0), trustedStages.size() - 1);
            return trustedStages.get(index);
        }

        private String dimensionName(TurnEvaluationRow row, String roundName) {
            return english
                    ? "Round " + (row.turnIndex() + 1) + " · " + roundName
                    : "第" + (row.turnIndex() + 1) + "轮 · " + roundName;
        }

        private String evaluationSummary(TurnEvaluationRow row) {
            String name = roundName(row);
            return english
                    ? "Round " + (row.turnIndex() + 1) + " · " + name
                            + " (" + row.score() + "/100): " + row.feedback()
                    : "第" + (row.turnIndex() + 1) + "轮 · " + name
                            + "（" + row.score() + " 分）：" + row.feedback();
        }

        private String evidence(TurnEvaluationRow row) {
            String name = roundName(row);
            return english
                    ? "Round " + (row.turnIndex() + 1) + " \"" + name
                            + "\" score: " + row.score() + "/100"
                    : "第" + (row.turnIndex() + 1) + "轮“" + name
                            + "”评分 " + row.score() + " 分";
        }

        private String summary(int turnCount, int totalScore, String priorityFeedback) {
            return english
                    ? "Completed " + turnCount + " rounds with an overall average score of "
                            + totalScore + "/100. Top improvement priority: " + priorityFeedback
                    : "本场完成 " + turnCount + " 轮，综合平均 " + totalScore
                            + " 分。优先改进：" + priorityFeedback;
        }

        private List<String> nextPlan() {
            return english
                    ? List.of(
                            "Rewrite the lowest-scoring response using the feedback above",
                            "Add one specific example and explain its relevance clearly",
                            "Complete a timed IELTS speaking attempt and compare it with this report")
                    : List.of(
                            "先按反馈重写最低分轮次的回答",
                            "补充一个可验证的具体事例或数据",
                            "完成一次同类型限时复述并与本报告对照");
        }

        private List<String> recommendedDrills(int score) {
            if (english) {
                return score < 70
                        ? List.of(
                                "IELTS answer structure practice",
                                "Two-minute timed speaking",
                                "Specific examples and broader language range")
                        : List.of(
                                "IELTS follow-up question practice",
                                "Concise speaking with clear signposting");
            }
            return score < 70
                    ? List.of("STAR 结构拆解", "一题两分钟限时表达", "证据与结果量化")
                    : List.of("追问压力测试", "答案压缩与重点前置");
        }

        private String readinessLevel(int score) {
            if (score >= 85) {
                return english ? "Well prepared" : "准备充分";
            }
            if (score >= 70) {
                return english ? "Mostly ready" : "基本就绪";
            }
            return english ? "More practice needed" : "需要加强";
        }

        private String scoreExplanation(int turnCount) {
            return english
                    ? "The total score is the rounded arithmetic mean of the " + turnCount
                            + " turn scores and is provided for practice guidance only."
                    : "总分为本场 " + turnCount
                            + " 个轮次评分的算术平均值（四舍五入），仅用于训练参考。";
        }
    }

}
