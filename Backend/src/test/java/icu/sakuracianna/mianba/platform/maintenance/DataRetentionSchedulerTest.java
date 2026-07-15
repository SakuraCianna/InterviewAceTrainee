package icu.sakuracianna.mianba.platform.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import icu.sakuracianna.mianba.interview.service.SessionDeletionCoordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class DataRetentionSchedulerTest {

    @Test
    void purgeOnlyDeletesOldUnreferencedMaterialTombstones() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc,
                immediateTransactions(),
                mock(SessionDeletionCoordinator.class));

        scheduler.enforceRetention();

        String sql = mockingDetails(jdbc).getInvocations().stream()
                .map(invocation -> invocation.getArguments().length == 0
                        ? ""
                        : String.valueOf(invocation.getArguments()[0]))
                .collect(Collectors.joining("\n"));
        assertThat(sql)
                .contains("job.status IN ('QUEUED', 'RUNNING', 'RETRYING')")
                .contains("session_row.status IN ('cancelled', 'deleting', 'deleted')")
                .contains("stage = 'SESSION_CLOSED'")
                .contains("turn_row.status IN ('waiting_answer', 'processing')")
                .contains("material.status = 'deleted'")
                .contains("material.updated_at <= now() - interval '7 days'")
                .contains("SELECT 1 FROM sessions session_row")
                .contains("session_row.material_id = material.id")
                .contains("SELECT 1 FROM ai_jobs job")
                .contains("job.material_id = material.id")
                .contains("LIMIT 500");
    }

    @Test
    void materialAnonymizationReplacesBothContentHashesWithRecordScopedTombstones() {
        MaterialRetentionJdbcTemplate jdbc = new MaterialRetentionJdbcTemplate(UUID.randomUUID());
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc,
                immediateTransactions(),
                mock(SessionDeletionCoordinator.class));

        scheduler.enforceRetention();

        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql)
                .contains("source_sha256 = encode(digest(")
                .contains("'mianba:material-source-erased:v1:' || id::text, 'sha256'")
                .contains("request_hash = encode(digest(")
                .contains("'mianba:material-request-erased:v1:' || id::text, 'sha256'"));
    }

    @Test
    void oneSessionErasureFailureDoesNotBlockFollowingSessionOrInflateSuccessCount() {
        UUID failedId = UUID.randomUUID();
        UUID successfulId = UUID.randomUUID();
        RetentionQueryJdbcTemplate jdbc = new RetentionQueryJdbcTemplate(List.of(failedId, successfulId));
        SessionDeletionCoordinator deletions = mock(SessionDeletionCoordinator.class);
        doThrow(new IllegalStateException("不得写入日志的模拟敏感详情"))
                .when(deletions).resumeDeletion(failedId);
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc, immediateTransactions(), deletions);

        int succeeded = scheduler.eraseExpiredSessionContent();

        assertThat(succeeded).isEqualTo(1);
        verify(deletions).resumeDeletion(failedId);
        verify(deletions).resumeDeletion(successfulId);
    }

    private static final class RetentionQueryJdbcTemplate extends JdbcTemplate {
        private final List<UUID> pendingDeletes;
        private final List<String> queries = new ArrayList<>();

        private RetentionQueryJdbcTemplate(List<UUID> pendingDeletes) {
            this.pendingDeletes = pendingDeletes;
        }

        @Override
        public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            if (!sql.contains("WHERE status = 'deleting'")) {
                return List.of();
            }
            return pendingDeletes.stream().map(id -> map(mapper, id)).toList();
        }

        private static <T> T map(org.springframework.jdbc.core.RowMapper<T> mapper, UUID id) {
            java.sql.ResultSet resultSet = mock(java.sql.ResultSet.class);
            try {
                org.mockito.Mockito.when(resultSet.getObject("id", UUID.class)).thenReturn(id);
                return mapper.mapRow(resultSet, 0);
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class MaterialRetentionJdbcTemplate extends JdbcTemplate {
        private final UUID materialId;
        private final List<String> updates = new ArrayList<>();

        private MaterialRetentionJdbcTemplate(UUID materialId) {
            this.materialId = materialId;
        }

        @Override
        public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            if (!sql.contains("FROM materials") || !sql.contains("retention_until <= now()")) {
                return List.of();
            }
            return List.of(RetentionQueryJdbcTemplate.map(mapper, materialId));
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 1;
        }

        @Override
        public int update(String sql) {
            updates.add(sql);
            return 1;
        }
    }

    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // 测试事务立即提交，不需要资源清理。
            }

            @Override
            public void rollback(TransactionStatus status) {
                // 测试事务没有外部资源，回滚由 mock 行为表达。
            }
        });
    }
}
