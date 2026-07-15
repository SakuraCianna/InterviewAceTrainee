package icu.sakuracianna.mianba.interview.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 擦除单次面试产生的用户内容，只保留会话、账务和运营所需的最小元数据。
 *
 * 任务表与会话内容表必须由调用方放在不同短事务中处理。这样可以避免 Worker 的
 * 任务锁与会话锁和删除流程形成循环等待，同时让失败步骤能够安全重复执行。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public final class SessionContentEraser {
    private final JdbcTemplate jdbc;

    public SessionContentEraser(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void eraseJobs(UUID sessionId, String cancellationCode) {
        jdbc.update("""
                UPDATE ai_jobs
                SET status = CASE
                        WHEN status IN ('QUEUED', 'RUNNING', 'RETRYING') THEN 'CANCELLED'
                        ELSE status
                    END,
                    stage = CASE
                        WHEN status IN ('QUEUED', 'RUNNING', 'RETRYING') THEN ?
                        ELSE stage
                    END,
                    progress = CASE
                        WHEN status IN ('QUEUED', 'RUNNING', 'RETRYING') THEN 0
                        ELSE progress
                    END,
                    retryable = false,
                    request_hash = encode(digest('mianba:erased-ai-job:v1', 'sha256'), 'hex'),
                    input_ref = '{}'::jsonb,
                    result_ref = NULL,
                    error_code = CASE
                        WHEN status IN ('QUEUED', 'RUNNING', 'RETRYING') THEN ?
                        ELSE error_code
                    END,
                    error_message = NULL,
                    lease_owner = NULL,
                    lease_until = NULL,
                    next_attempt_at = NULL,
                    version = version + 1,
                    updated_at = now()
                WHERE session_id = ?
                """, cancellationCode, cancellationCode, sessionId);
    }

    void eraseConversation(UUID sessionId) {
        jdbc.update("""
                UPDATE turns
                SET round_name = '内容已删除',
                    question_text = '该轮内容已按隐私策略删除。',
                    answer_text = NULL,
                    answer_idempotency_key = NULL,
                    evaluation_score = NULL,
                    evaluation_feedback = NULL,
                    evaluated_at = NULL,
                    status = 'cancelled'
                WHERE session_id = ?
                """, sessionId);
        jdbc.update("DELETE FROM reports WHERE session_id = ?", sessionId);
        jdbc.update("""
                UPDATE content_safety
                SET matched_terms = '[]'::jsonb, content_excerpt = NULL
                WHERE session_id = ?
                """, sessionId);
    }
}
