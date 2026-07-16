package icu.sakuracianna.mianba.aiwork.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 回收因 Worker 进程退出或网络中断而过期的 AI 任务租约。
 *
 * 可重试任务通过新 Outbox 事件再次投递。超过尝试上限或任务有效期的任务进入终态；
 * 仅仍处于有效期内的 awaiting_ai 会话恢复为可回答，删除态或过期会话不会被重新打开。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class AiLeaseRecovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiLeaseRecovery.class);
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AiLeaseRecovery(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 分批回收过期租约并创建必要的重试事件。
     * 使用 {@code SKIP LOCKED} 是为了允许多个 API 副本并行扫描而不重复创建恢复事件。
     */
    @Scheduled(fixedDelayString = "${mianba.ai.lease-recovery-delay-ms:15000}")
    @Transactional
    public void recoverExpiredLeases() {
        Instant now = clock.instant();
        List<LeaseRow> rows = jdbc.query("""
                SELECT id, session_id, attempt, max_attempts, manual_retry_count, version, expires_at
                FROM ai_jobs
                WHERE status = 'RUNNING' AND lease_until <= ?
                ORDER BY lease_until, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new LeaseRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getInt("attempt"),
                resultSet.getInt("max_attempts"),
                resultSet.getInt("manual_retry_count"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("expires_at").toInstant()),
                Timestamp.from(now), BATCH_SIZE);
        for (LeaseRow row : rows) {
            RecoveryAction action = decide(row.attempt(), row.maxAttempts(), row.expiresAt(), now);
            if (action == RecoveryAction.RETRY) {
                scheduleRetry(row, now);
            } else {
                finishTerminal(row, action);
            }
        }
        if (!rows.isEmpty()) {
            LOGGER.warn("Recovered expired AI worker leases count={}", rows.size());
        }
    }

    static RecoveryAction decide(int attempt, int maxAttempts, Instant expiresAt, Instant now) {
        if (!now.isBefore(expiresAt)) {
            return RecoveryAction.EXPIRED;
        }
        return attempt >= maxAttempts ? RecoveryAction.EXHAUSTED : RecoveryAction.RETRY;
    }

    static boolean manualRetryAllowed(boolean expired, int manualRetryCount) {
        return !expired && manualRetryCount < 1;
    }

    private void scheduleRetry(LeaseRow row, Instant now) {
        Instant availableAt = now.plus(retryDelay(row.attempt()));
        if (!availableAt.isBefore(row.expiresAt())) {
            availableAt = now;
        }
        long nextVersion = row.version() + 1;
        int changed = jdbc.update("""
                UPDATE ai_jobs
                SET status = 'RETRYING', stage = 'LEASE_RECOVERY', progress = 0,
                    error_code = 'WORKER_LEASE_EXPIRED', error_message = 'Worker 租约已过期，任务将自动重试',
                    retryable = true, lease_owner = NULL, lease_until = NULL,
                    next_attempt_at = ?, version = ?, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND version = ?
                """, Timestamp.from(availableAt), nextVersion, row.id(), row.version());
        requireChanged(changed, row.id());

        AiJobEnvelope envelope = AiJobEnvelope.create(
                row.id(), "lease-recovery:" + row.id(), "lease-recovery:" + row.id(), now);
        jdbc.update("""
                INSERT INTO outbox_events(
                    aggregate_type, aggregate_id, aggregate_version, event_type,
                    payload, correlation_id, trace_id, available_at)
                VALUES ('AI_JOB', ?, ?, 'AI_JOB_QUEUED', ?::jsonb, ?, ?, ?)
                """, row.id(), nextVersion, writeJson(envelope),
                envelope.correlationId(), envelope.traceId(), Timestamp.from(availableAt));
    }

    private void finishTerminal(LeaseRow row, RecoveryAction action) {
        boolean expired = action == RecoveryAction.EXPIRED;
        boolean manualRetryAllowed = manualRetryAllowed(expired, row.manualRetryCount());
        int changed = jdbc.update("""
                UPDATE ai_jobs
                SET status = ?, stage = ?, progress = 0,
                    error_code = ?, error_message = ?, retryable = ?,
                    lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND version = ?
                """,
                expired ? "CANCELLED" : "FAILED",
                expired ? "TASK_EXPIRED" : "NEEDS_ATTENTION",
                expired ? "TASK_EXPIRED" : "WORKER_LEASE_EXHAUSTED",
                expired ? "任务已过期" : "Worker 多次中断，请人工重试",
                manualRetryAllowed,
                row.id(), row.version());
        requireChanged(changed, row.id());
        restoreInterviewContext(
                row.sessionId(), expired ? "TASK_EXPIRED" : "WORKER_LEASE_EXHAUSTED");
    }

    /** 只有会话仍等待当前任务且未过期时才恢复轮次，避免删除或超时竞态重新开放答题。 */
    void restoreInterviewContext(UUID sessionId, String failureCode) {
        if (sessionId == null) {
            return;
        }
        int sessionChanged = jdbc.update("""
                UPDATE sessions
                SET status = 'active', failure_code = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'awaiting_ai' AND expires_at > now()
                """, failureCode, sessionId);
        if (sessionChanged != 1) {
            return;
        }
        jdbc.update("""
                UPDATE turns SET status = 'waiting_answer'
                WHERE session_id = ? AND status = 'processing'
                """, sessionId);
    }

    private String writeJson(AiJobEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize lease recovery event", exception);
        }
    }

    private static void requireChanged(int changed, UUID jobId) {
        if (changed != 1) {
            throw new IllegalStateException("AI job lease changed during recovery: " + jobId);
        }
    }

    private static Duration retryDelay(int attempt) {
        long[] delays = {5, 20, 60};
        return Duration.ofSeconds(delays[Math.min(Math.max(attempt - 1, 0), delays.length - 1)]);
    }

    enum RecoveryAction {
        RETRY,
        EXHAUSTED,
        EXPIRED
    }

    private record LeaseRow(
            UUID id,
            UUID sessionId,
            int attempt,
            int maxAttempts,
            int manualRetryCount,
            long version,
            Instant expiresAt) {
    }
}
