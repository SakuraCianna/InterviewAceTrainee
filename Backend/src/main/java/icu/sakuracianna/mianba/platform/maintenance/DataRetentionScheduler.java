package icu.sakuracianna.mianba.platform.maintenance;

import icu.sakuracianna.mianba.interview.service.SessionDeletionCoordinator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分批执行会话超时、内容擦除和无引用材料清理策略。
 *
 * 每个阶段只持有单类业务行锁，避免低配服务器上的长事务与 Worker 形成循环等待。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class DataRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataRetentionScheduler.class);
    private static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SessionDeletionCoordinator deletions;

    public DataRetentionScheduler(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SessionDeletionCoordinator deletions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.deletions = deletions;
    }

    /** 每小时清理有界批次，避免维护任务挤占 API 和 Worker 的数据库连接。 */
    @Scheduled(fixedDelayString = "${mianba.maintenance.retention-delay-ms:3600000}")
    public void enforceRetention() {
        int reconciledArtifacts = reconcileClosedSessionArtifacts();
        int expiredSessions = cancelExpiredSessions();
        // 第二次对账覆盖本轮刚转为 cancelled、但后续独立事务尚未处理的任务和轮次。
        reconciledArtifacts += reconcileClosedSessionArtifacts();
        int erasedSessions = eraseExpiredSessionContent();
        int erasedMaterials = eraseExpiredMaterials();
        int purgedMaterials = purgeDeletedMaterials();
        int deletedOperationalLogs = deleteOldOperationalLogs();
        if (reconciledArtifacts > 0 || expiredSessions > 0 || erasedSessions > 0 || erasedMaterials > 0
                || purgedMaterials > 0 || deletedOperationalLogs > 0) {
            LOGGER.info(
                    "Retention cleanup completed reconciled_artifacts={} expired_sessions={} erased_sessions={} "
                            + "erased_materials={} purged_materials={} operational_logs={}",
                    reconciledArtifacts, expiredSessions, erasedSessions, erasedMaterials,
                    purgedMaterials, deletedOperationalLogs);
        }
    }

    private int cancelExpiredSessions() {
        List<UUID> ids = jdbc.query("""
                SELECT id FROM sessions
                WHERE status IN ('created', 'active', 'awaiting_ai') AND expires_at <= now()
                ORDER BY expires_at, id
                LIMIT ?
                """, (rs, row) -> rs.getObject("id", UUID.class), BATCH_SIZE);
        int cancelled = 0;
        for (UUID id : ids) {
            Integer changed = transactions.execute(status -> jdbc.update("""
                    UPDATE sessions
                    SET status = 'cancelled', failure_code = 'SESSION_EXPIRED', ended_at = now(),
                        version = version + 1, updated_at = now()
                    WHERE id = ? AND status IN ('created', 'active', 'awaiting_ai')
                      AND expires_at <= now()
                    """, id));
            if (changed == null || changed != 1) {
                continue;
            }
            cancelled++;
        }
        return cancelled;
    }

    /**
     * 修复分阶段取消可能留下的活动任务和轮次。
     *
     * 会话、任务和轮次分别提交，避免与 Worker 的 job、session、turn 加锁顺序形成环路。
     * 若进程在任一阶段退出，下一轮维护任务仍会根据会话终态继续收敛。
     */
    private int reconcileClosedSessionArtifacts() {
        int jobs = Objects.requireNonNull(transactions.execute(status -> jdbc.update("""
                WITH candidates AS (
                    SELECT job.id
                    FROM ai_jobs job
                    WHERE job.status IN ('QUEUED', 'RUNNING', 'RETRYING')
                      AND EXISTS (
                          SELECT 1 FROM sessions session_row
                          WHERE session_row.id = job.session_id
                            AND session_row.status IN ('cancelled', 'deleting', 'deleted'))
                    ORDER BY job.updated_at, job.id
                    LIMIT ?
                    FOR UPDATE OF job SKIP LOCKED
                )
                UPDATE ai_jobs job
                SET status = 'CANCELLED', stage = 'SESSION_CLOSED', progress = 0,
                    retryable = false, error_code = 'SESSION_CLOSED', error_message = NULL,
                    lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL,
                    version = version + 1, updated_at = now()
                FROM candidates
                WHERE job.id = candidates.id
                """, BATCH_SIZE)), "Closed-session job reconciliation returned no result");
        int turns = Objects.requireNonNull(transactions.execute(status -> jdbc.update("""
                WITH candidates AS (
                    SELECT turn_row.id
                    FROM turns turn_row
                    WHERE turn_row.status IN ('waiting_answer', 'processing')
                      AND EXISTS (
                          SELECT 1 FROM sessions session_row
                          WHERE session_row.id = turn_row.session_id
                            AND session_row.status IN ('cancelled', 'deleting', 'deleted'))
                    ORDER BY turn_row.created_at, turn_row.id
                    LIMIT ?
                    FOR UPDATE OF turn_row SKIP LOCKED
                )
                UPDATE turns turn_row
                SET status = 'cancelled'
                FROM candidates
                WHERE turn_row.id = candidates.id
                """, BATCH_SIZE)), "Closed-session turn reconciliation returned no result");
        return jobs + turns;
    }

    /**
     * 分别恢复用户删除和到期擦除；单条失败不阻断同批其他会话。
     *
     * 失败项不计入成功数，并由下一轮调度依据幂等状态重新处理。
     */
    int eraseExpiredSessionContent() {
        List<UUID> pendingDeletes = jdbc.query("""
                SELECT id FROM sessions
                WHERE status = 'deleting' AND content_erased_at IS NULL
                ORDER BY updated_at, id
                LIMIT ?
                """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), BATCH_SIZE);
        int succeeded = eraseSessionsIndependently(
                pendingDeletes, deletions::resumeDeletion, "resume_delete");

        List<UUID> expiredContent = jdbc.query("""
                SELECT id FROM sessions
                WHERE status IN ('completed', 'cancelled', 'deleted')
                  AND content_erased_at IS NULL
                  AND ended_at <= now() - interval '90 days'
                ORDER BY ended_at, id
                LIMIT ?
                """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), BATCH_SIZE);
        succeeded += eraseSessionsIndependently(
                expiredContent, deletions::eraseRetainedContent, "erase_retained_content");
        return succeeded;
    }

    private int eraseSessionsIndependently(
            List<UUID> sessionIds,
            Consumer<UUID> erasure,
            String operation) {
        int succeeded = 0;
        for (UUID sessionId : sessionIds) {
            try {
                erasure.accept(sessionId);
                succeeded++;
            } catch (RuntimeException exception) {
                // 异常消息可能携带下游内容，只记录操作类型与会话标识。
                LOGGER.warn("retention_session_erasure_failed operation={} session_id={}",
                        operation, sessionId);
            }
        }
        return succeeded;
    }

    private int eraseExpiredMaterials() {
        return Objects.requireNonNull(transactions.execute(status -> {
            List<UUID> ids = jdbc.query("""
                    SELECT id FROM materials
                    WHERE status <> 'deleted' AND retention_until <= now()
                    ORDER BY retention_until, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> rs.getObject("id", UUID.class), BATCH_SIZE);
            for (UUID id : ids) {
                // 不复用原内容哈希；不同固定域加记录标识可生成稳定且互不关联的 64 位墓碑。
                jdbc.update("""
                        UPDATE materials
                        SET status = 'deleted', resume_filename = NULL, resume_content_type = NULL,
                            resume_size_bytes = NULL, resume_text = NULL, job_title = NULL,
                            job_requirements = NULL, target_school = NULL, major = NULL,
                            research_direction = NULL, profile_summary = '', keywords = '[]'::jsonb,
                            embedding = NULL,
                            source_sha256 = encode(digest(
                                'mianba:material-source-erased:v1:' || id::text, 'sha256'), 'hex'),
                            request_hash = encode(digest(
                                'mianba:material-request-erased:v1:' || id::text, 'sha256'), 'hex'),
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND status <> 'deleted'
                        """, id);
            }
            return ids.size();
        }), "Material retention transaction returned no result");
    }

    private int purgeDeletedMaterials() {
        return Objects.requireNonNull(transactions.execute(status -> jdbc.update("""
                WITH candidates AS (
                    SELECT material.id
                    FROM materials material
                    WHERE material.status = 'deleted'
                      AND material.updated_at <= now() - interval '7 days'
                      AND NOT EXISTS (
                          SELECT 1 FROM sessions session_row
                          WHERE session_row.material_id = material.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM ai_jobs job
                          WHERE job.material_id = material.id)
                    ORDER BY material.updated_at, material.id
                    LIMIT 500
                    FOR UPDATE OF material SKIP LOCKED
                )
                DELETE FROM materials material
                USING candidates
                WHERE material.id = candidates.id
                """)), "Material purge transaction returned no result");
    }

    private int deleteOldOperationalLogs() {
        return Objects.requireNonNull(transactions.execute(status -> {
            int deleted = jdbc.update("""
                DELETE FROM auth_login WHERE id IN (
                    SELECT id FROM auth_login WHERE created_at < now() - interval '90 days'
                    ORDER BY created_at LIMIT 1000)
                """);
        deleted += jdbc.update("""
                DELETE FROM ai_call_logs WHERE id IN (
                    SELECT id FROM ai_call_logs WHERE created_at < now() - interval '90 days'
                    ORDER BY created_at LIMIT 1000)
                """);
        deleted += jdbc.update("""
                DELETE FROM content_safety WHERE id IN (
                    SELECT id FROM content_safety WHERE created_at < now() - interval '180 days'
                    ORDER BY created_at LIMIT 1000)
                """);
        deleted += jdbc.update("""
                DELETE FROM outbox_events WHERE id IN (
                    SELECT id FROM outbox_events
                    WHERE published_at < now() - interval '30 days'
                    ORDER BY published_at LIMIT 1000)
                """);
        deleted += jdbc.update("""
                DELETE FROM processed_messages WHERE (consumer_name, message_id) IN (
                    SELECT consumer_name, message_id FROM processed_messages
                    WHERE processed_at < now() - interval '90 days'
                    ORDER BY processed_at LIMIT 1000)
                """);
            return deleted;
        }), "Operational log retention transaction returned no result");
    }
}
