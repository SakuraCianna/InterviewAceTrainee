package icu.sakuracianna.mianba.interview.service;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 编排可恢复的面试删除流程，确保删除后不可继续答题或被 Worker 写回内容。
 *
 * 删除被拆成会话封禁、任务擦除、内容擦除和最终确认四个短事务。任一步骤失败时，
 * 会话会停留在 deleting 状态并由留存任务继续执行，不会因为回滚而重新暴露内容入口。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public final class SessionDeletionCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SessionContentEraser eraser;

    public SessionDeletionCoordinator(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SessionContentEraser eraser) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.eraser = eraser;
    }

    /**
     * 删除指定用户的面试内容；重复删除已完成的会话不会产生额外副作用。
     *
     * @param userId 当前用户标识
     * @param sessionId 待删除的面试会话标识
     */
    public void delete(UUID userId, UUID sessionId) {
        DeletionDecision decision = Objects.requireNonNull(
                transactions.execute(status -> markDeleting(userId, sessionId)),
                "Deletion marker transaction returned no result");
        if (decision == DeletionDecision.ALREADY_ERASED) {
            return;
        }
        finishDeletion(sessionId);
    }

    /**
     * 继续执行异常中断的删除流程，供定时维护任务幂等恢复。
     *
     * @param sessionId 处于 deleting 状态的会话标识
     */
    public void resumeDeletion(UUID sessionId) {
        DeletionDecision decision = Objects.requireNonNull(
                transactions.execute(status -> resumeDecision(sessionId)),
                "Deletion resume transaction returned no result");
        if (decision != DeletionDecision.NEEDS_ERASURE) {
            return;
        }
        finishDeletion(sessionId);
    }

    /**
     * 擦除超过留存期的终态会话内容，不改变原有 completed 或 cancelled 状态。
     *
     * @param sessionId 超过留存期的会话标识
     */
    public void eraseRetainedContent(UUID sessionId) {
        // 调用方筛选不是授权边界；每次执行仍需自行复核终态与 90 天留存条件。
        Boolean eligible = transactions.execute(status -> isRetentionErasureEligible(sessionId));
        if (!Boolean.TRUE.equals(eligible)) {
            return;
        }
        transactions.executeWithoutResult(status -> eraser.eraseJobs(sessionId, "CONTENT_RETENTION"));
        transactions.executeWithoutResult(status -> eraser.eraseConversation(sessionId));
        transactions.executeWithoutResult(status -> markRetentionErased(sessionId));
    }

    private boolean isRetentionErasureEligible(UUID sessionId) {
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM sessions
                    WHERE id = ? AND content_erased_at IS NULL
                      AND status IN ('completed', 'cancelled', 'deleted')
                      AND ended_at <= now() - interval '90 days')
                """, Boolean.class, sessionId);
        return Boolean.TRUE.equals(eligible);
    }

    private void markRetentionErased(UUID sessionId) {
        int changed = jdbc.update("""
                UPDATE sessions
                SET content_erased_at = now(), version = version + 1, updated_at = now()
                WHERE id = ? AND content_erased_at IS NULL
                  AND status IN ('completed', 'cancelled', 'deleted')
                  AND ended_at <= now() - interval '90 days'
                """, sessionId);
        if (changed == 1 || retainedContentAlreadyErased(sessionId)) {
            return;
        }
        throw new IllegalStateException("Interview retention state changed before erasure was recorded");
    }

    private DeletionDecision markDeleting(UUID userId, UUID sessionId) {
        SessionDeletionState state = jdbc.query("""
                SELECT status, content_erased_at
                FROM sessions
                WHERE id = ? AND user_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new SessionDeletionState(
                resultSet.getString("status"),
                resultSet.getTimestamp("content_erased_at")), sessionId, userId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "interview_session_not_found", "面试训练不存在"));
        if ("deleted".equals(state.status()) && state.contentErasedAt() != null) {
            return DeletionDecision.ALREADY_ERASED;
        }
        if (!"deleting".equals(state.status()) && !"deleted".equals(state.status())) {
            int changed = jdbc.update("""
                    UPDATE sessions
                    SET status = 'deleting', ended_at = COALESCE(ended_at, now()),
                        version = version + 1, updated_at = now()
                    WHERE id = ? AND user_id = ?
                    """, sessionId, userId);
            if (changed != 1) {
                throw new IllegalStateException("Interview deletion marker was not recorded");
            }
        }
        return DeletionDecision.NEEDS_ERASURE;
    }

    private DeletionDecision resumeDecision(UUID sessionId) {
        return jdbc.query("""
                SELECT status, content_erased_at
                FROM sessions
                WHERE id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new SessionDeletionState(
                resultSet.getString("status"),
                resultSet.getTimestamp("content_erased_at")), sessionId)
                .stream().findFirst()
                .map(state -> {
                    if ("deleted".equals(state.status()) && state.contentErasedAt() != null) {
                        return DeletionDecision.ALREADY_ERASED;
                    }
                    if ("deleting".equals(state.status()) || "deleted".equals(state.status())) {
                        return DeletionDecision.NEEDS_ERASURE;
                    }
                    return DeletionDecision.NOT_ALLOWED;
                })
                .orElse(DeletionDecision.NOT_ALLOWED);
    }

    private void finishDeletion(UUID sessionId) {
        // 任务、轮次和会话分别提交，任何阶段都不同时持有 job 与 session 行锁。
        transactions.executeWithoutResult(status -> eraser.eraseJobs(sessionId, "SESSION_DELETED"));
        transactions.executeWithoutResult(status -> eraser.eraseConversation(sessionId));
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE sessions
                    SET status = 'deleted', content_erased_at = now(),
                        version = version + 1, updated_at = now()
                    WHERE id = ? AND status IN ('deleting', 'deleted')
                      AND content_erased_at IS NULL
                    """, sessionId);
            if (changed != 1 && !deletionAlreadyCompleted(sessionId)) {
                throw new IllegalStateException("Interview deletion final state was not recorded");
            }
        });
    }

    private boolean deletionAlreadyCompleted(UUID sessionId) {
        Boolean completed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM sessions
                    WHERE id = ? AND status = 'deleted' AND content_erased_at IS NOT NULL)
                """, Boolean.class, sessionId);
        return Boolean.TRUE.equals(completed);
    }

    private boolean retainedContentAlreadyErased(UUID sessionId) {
        Boolean erased = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM sessions
                    WHERE id = ? AND content_erased_at IS NOT NULL)
                """, Boolean.class, sessionId);
        return Boolean.TRUE.equals(erased);
    }

    private enum DeletionDecision {
        ALREADY_ERASED,
        NEEDS_ERASURE,
        NOT_ALLOWED
    }

    record SessionDeletionState(String status, Timestamp contentErasedAt) {
    }
}
