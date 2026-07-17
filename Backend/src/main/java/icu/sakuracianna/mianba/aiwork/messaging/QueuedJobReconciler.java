package icu.sakuracianna.mianba.aiwork.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
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

/**
 * 对账已长时间排队但没有待发布 Outbox 的任务，修复 broker 异常丢消息造成的永久卡住。
 *
 * 重复投递由 Worker 的任务行锁和 {@code processed_messages} 去重承接。每次补发都会提升任务版本，
 * 使 Outbox 唯一约束能够区分真正的新消息。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class QueuedJobReconciler {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueuedJobReconciler.class);
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public QueuedJobReconciler(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 分批补发超过两分钟仍未被 Worker 领取的任务，并回收已过期任务。 */
    @Scheduled(fixedDelayString = "${mianba.ai.queued-reconcile-delay-ms:60000}")
    @Transactional
    public void reconcile() {
        expireQueuedJobs();
        List<QueuedRow> rows = jdbc.query("""
                SELECT j.id, j.version
                FROM ai_jobs j
                WHERE j.status = 'QUEUED'
                  AND j.updated_at <= now() - interval '2 minutes'
                  AND j.expires_at > now()
                  AND NOT EXISTS (
                      SELECT 1 FROM outbox_events o
                      WHERE o.aggregate_id = j.id AND o.published_at IS NULL)
                ORDER BY j.updated_at, j.id
                LIMIT ?
                FOR UPDATE OF j SKIP LOCKED
                """, (rs, row) -> new QueuedRow(
                rs.getObject("id", UUID.class), rs.getLong("version")), BATCH_SIZE);
        Instant now = clock.instant();
        for (QueuedRow row : rows) {
            long nextVersion = row.version() + 1;
            jdbc.update("""
                    UPDATE ai_jobs
                    SET stage = 'QUEUE_RECONCILIATION', version = ?, updated_at = now()
                    WHERE id = ? AND status = 'QUEUED' AND version = ?
                    """, nextVersion, row.id(), row.version());
            AiJobEnvelope envelope = AiJobEnvelope.create(
                    row.id(), "queue-reconcile:" + row.id(), "queue-reconcile:" + row.id(), now);
            jdbc.update("""
                    INSERT INTO outbox_events(
                        aggregate_type, aggregate_id, aggregate_version, event_type,
                        payload, correlation_id, trace_id)
                    VALUES ('AI_JOB', ?, ?, 'AI_JOB_QUEUED', ?::jsonb, ?, ?)
                    """, row.id(), nextVersion, writeJson(envelope),
                    envelope.correlationId(), envelope.traceId());
        }
        if (!rows.isEmpty()) {
            LOGGER.warn("Re-enqueued stale AI jobs count={}", rows.size());
        }
    }

    private void expireQueuedJobs() {
        List<UUID> sessions = jdbc.query("""
                UPDATE ai_jobs
                SET status = 'CANCELLED', stage = 'TASK_EXPIRED', retryable = false,
                    error_code = 'TASK_EXPIRED', error_message = '任务已过期',
                    version = version + 1, updated_at = now()
                WHERE id IN (
                    SELECT id FROM ai_jobs
                    WHERE status = 'QUEUED' AND expires_at <= now()
                    ORDER BY expires_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING session_id
                """, (rs, row) -> rs.getObject("session_id", UUID.class), BATCH_SIZE);
        for (UUID sessionId : sessions) {
            if (sessionId == null) {
                continue;
            }
            restoreInterviewContext(sessionId);
        }
    }

    /** 任务过期只恢复仍有效的等待会话，状态已变化的会话和轮次保持不动。 */
    void restoreInterviewContext(UUID sessionId) {
        int sessionChanged = jdbc.update("""
                    UPDATE sessions SET status = 'active', failure_code = 'TASK_EXPIRED',
                        version = version + 1, updated_at = now()
                    WHERE id = ? AND status = 'awaiting_ai' AND expires_at > now()
                    """, sessionId);
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
            throw new IllegalStateException("Unable to serialize queue reconciliation event", exception);
        }
    }

    private record QueuedRow(UUID id, long version) {
    }
}
