package icu.sakuracianna.mianba.aiwork.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import icu.sakuracianna.mianba.aiwork.messaging.AiJobEnvelope;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL 任务查询与人工重试实现；重试会原子更新任务并写入新 Outbox 事件。 */
@Service
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class JdbcTaskService implements TaskService {
    /** 管理端只展示任务运营元数据，不能回传模型结果或可能包含用户内容的错误详情。 */
    private static final String REDACTED_TASK_COLUMNS = """
            id, session_id, kind, status, stage, progress, attempt, max_attempts,
            retryable, version, NULL::text AS result_ref_json,
            error_code, NULL::varchar AS error_message, created_at, updated_at
            """;
    /** 会话进入删除流程后立即切断用户任务查询，避免后台擦除完成前的竞态泄露。 */
    private static final String USER_VISIBLE_SESSION_PREDICATE = """
             AND (job.session_id IS NULL OR EXISTS (
                 SELECT 1 FROM sessions session_row
                 WHERE session_row.id = job.session_id
                   AND session_row.status NOT IN ('deleting', 'deleted')))
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcTaskService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public TaskView get(UUID requesterId, boolean admin, UUID taskId) {
        String sql = "SELECT " + (admin ? REDACTED_TASK_COLUMNS : TaskRowMapper.COLUMNS)
                + " FROM ai_jobs job WHERE job.id = ?"
                + (admin ? "" : " AND job.owner_id = ?" + USER_VISIBLE_SESSION_PREDICATE);
        Object[] arguments = admin ? new Object[]{taskId} : new Object[]{taskId, requesterId};
        return jdbc.query(sql, (rs, row) -> TaskRowMapper.map(rs, row, mapper), arguments).stream()
                .findFirst()
                .orElseThrow(() -> notFound());
    }

    @Override
    public Optional<TaskView> findByOwnerAndIdempotency(UUID ownerId, String idempotencyKey) {
        return jdbc.query(
                        "SELECT " + TaskRowMapper.COLUMNS
                                + " FROM ai_jobs job"
                                + " WHERE job.owner_id = ? AND job.idempotency_key = ?"
                                + USER_VISIBLE_SESSION_PREDICATE,
                        (rs, row) -> TaskRowMapper.map(rs, row, mapper), ownerId, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public Optional<TaskView> findCurrentForOwnerSession(UUID ownerId, UUID sessionId) {
        return jdbc.query(
                        "SELECT " + TaskRowMapper.COLUMNS
                                + " FROM ai_jobs job"
                                + " WHERE job.owner_id = ? AND job.session_id = ?"
                                + " AND job.status IN ('QUEUED','RUNNING','RETRYING','FAILED')"
                                + USER_VISIBLE_SESSION_PREDICATE
                                + " ORDER BY job.created_at DESC LIMIT 1",
                        (resultSet, rowNumber) -> TaskRowMapper.map(resultSet, rowNumber, mapper),
                        ownerId, sessionId)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public TaskView retry(
            UUID requesterId,
            boolean admin,
            UUID taskId,
            String idempotencyKey,
            String requestId) {
        // 锁住任务行，使重复点击只能推进一次 version，并让 Outbox 唯一约束承担最终幂等兜底。
        String retrySql = """
                SELECT owner_id, session_id, status, retryable, version,
                       manual_retry_count, manual_retry_key
                FROM ai_jobs job
                WHERE job.id = ?
                """ + (admin ? "" : " AND job.owner_id = ?" + USER_VISIBLE_SESSION_PREDICATE)
                + " FOR UPDATE";
        Object[] retryArguments = admin ? new Object[]{taskId} : new Object[]{taskId, requesterId};
        List<RetryRow> rows = jdbc.query(retrySql, (rs, row) -> new RetryRow(
                rs.getObject("owner_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("retryable"),
                rs.getLong("version"),
                rs.getInt("manual_retry_count"),
                rs.getString("manual_retry_key")), retryArguments);
        RetryRow job = rows.stream().findFirst().orElseThrow(JdbcTaskService::notFound);
        if (!admin && !job.ownerId().equals(requesterId)) {
            throw notFound();
        }
        if (idempotencyKey.equals(job.manualRetryKey())) {
            return get(requesterId, admin, taskId);
        }
        if (job.manualRetryCount() >= 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "task_manual_retry_exhausted", "该任务的人工重试次数已用尽");
        }
        if (!"FAILED".equals(job.status()) || !job.retryable()) {
            throw new ApiException(HttpStatus.CONFLICT, "task_not_retryable", "当前任务不可重试");
        }

        restoreInterviewContext(job.sessionId());

        long nextVersion = job.version() + 1;
        jdbc.update("""
                UPDATE ai_jobs
                SET status = 'QUEUED', stage = 'WAITING_FOR_WORKER', progress = 0,
                    attempt = GREATEST(max_attempts - 1, 0), error_code = NULL, error_message = NULL,
                    lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL,
                    manual_retry_count = manual_retry_count + 1, manual_retry_key = ?,
                    version = ?, updated_at = now()
                WHERE id = ? AND version = ?
                """, idempotencyKey, nextVersion, taskId, job.version());
        insertOutbox(taskId, nextVersion, requestId);
        return get(requesterId, admin, taskId);
    }

    @Override
    public TaskPage list(String status, String kind, UUID sessionId, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" AND kind = ?");
            args.add(kind.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (sessionId != null) {
            where.append(" AND session_id = ?");
            args.add(sessionId);
        }
        Long totalValue = jdbc.queryForObject("SELECT count(*) FROM ai_jobs" + where, Long.class, args.toArray());
        long total = totalValue == null ? 0 : totalValue;
        args.add(safeLimit);
        args.add(safeOffset);
        List<TaskView> items = jdbc.query(
                "SELECT " + REDACTED_TASK_COLUMNS + " FROM ai_jobs" + where
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> TaskRowMapper.map(rs, row, mapper), args.toArray());
        return new TaskPage(items, total, safeLimit, safeOffset, safeOffset + items.size() < total);
    }

    private void insertOutbox(UUID taskId, long version, String requestId) {
        Instant now = clock.instant();
        try {
            AiJobEnvelope envelope = AiJobEnvelope.create(taskId, requestId, requestId, now);
            String payload = mapper.writeValueAsString(envelope);
            jdbc.update("""
                    INSERT INTO outbox_events(
                        aggregate_type, aggregate_id, aggregate_version, event_type,
                        payload, correlation_id, trace_id)
                    VALUES ('AI_JOB', ?, ?, 'AI_JOB_QUEUED', ?::jsonb, ?, ?)
                    """, taskId, version, payload, requestId, requestId);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize outbox message", exception);
        }
    }

    private void restoreInterviewContext(UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        int sessionChanged = jdbc.update("""
                UPDATE sessions
                SET status = 'awaiting_ai', version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'active'
                """, sessionId);
        int turnChanged = jdbc.update("""
                UPDATE turns SET status = 'processing'
                WHERE session_id = ?
                  AND turn_index = (SELECT current_turn_index FROM sessions WHERE id = ?)
                  AND status = 'waiting_answer' AND answer_text IS NOT NULL
                """, sessionId, sessionId);
        if (sessionChanged != 1 || turnChanged != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "task_context_changed", "面试状态已变化，不能重试旧任务");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "task_not_found", "任务不存在");
    }

    private record RetryRow(
            UUID ownerId,
            UUID sessionId,
            String status,
            boolean retryable,
            long version,
            int manualRetryCount,
            String manualRetryKey) {
    }
}
