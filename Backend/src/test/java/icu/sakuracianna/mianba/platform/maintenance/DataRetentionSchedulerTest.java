package icu.sakuracianna.mianba.platform.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import icu.sakuracianna.mianba.interview.service.SessionDeletionCoordinator;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    void packageExpiryRunsBeforeSessionExpiryWithBoundedPackageThenStageLockOrder() {
        UUID packageId = UUID.randomUUID();
        PackageRetentionJdbcTemplate jdbc = new PackageRetentionJdbcTemplate(
                List.of(packageId), List.of(), Map.of(packageId, 1), Map.of());
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc,
                immediateTransactions(),
                mock(SessionDeletionCoordinator.class));

        scheduler.enforceRetention();

        SqlCall packageSelection = jdbc.queryContaining("SELECT id FROM interview_packages");
        assertThat(packageSelection.sql())
                .contains("WHERE status = 'ACTIVE' AND expires_at <= now()")
                .contains("ORDER BY expires_at, id")
                .contains("LIMIT ?")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(packageSelection.arguments()).containsExactly(50);

        String stageUpdate = jdbc.updateContaining("UPDATE interview_package_stages").sql();
        assertThat(stageUpdate)
                .contains("status = 'EXPIRED'")
                .contains("status IN ('LOCKED', 'UNLOCKED', 'IN_PROGRESS')")
                .contains("version = version + 1")
                .doesNotContain("COMPLETED', 'CANCELLED");
        String packageUpdate = jdbc.updateContaining("UPDATE interview_packages").sql();
        assertThat(packageUpdate)
                .contains("status = 'EXPIRED'")
                .contains("completed_at = COALESCE(completed_at, now())")
                .contains("version = version + 1")
                .contains("status = 'ACTIVE'")
                .contains("expires_at <= now()");

        assertThat(jdbc.indexOfEvent("SELECT id FROM interview_packages"))
                .isLessThan(jdbc.indexOfEvent("SELECT id FROM sessions"));
        assertThat(jdbc.indexOfEvent("UPDATE interview_package_stages"))
                .isLessThan(jdbc.indexOfEvent("UPDATE interview_packages"));
    }

    @Test
    void packageExpiryReturnsOnlyPackagesActuallyChanged() {
        UUID changed = UUID.randomUUID();
        UUID unchanged = UUID.randomUUID();
        PackageRetentionJdbcTemplate jdbc = new PackageRetentionJdbcTemplate(
                List.of(changed, unchanged),
                List.of(),
                Map.of(changed, 1, unchanged, 0),
                Map.of());
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc,
                immediateTransactions(),
                mock(SessionDeletionCoordinator.class));

        int expired = invokeIntMethod(scheduler, "expireJobInterviewPackages");

        assertThat(expired).isEqualTo(1);
        assertThat(jdbc.updatesMatching("UPDATE interview_package_stages")).hasSize(2);
        assertThat(jdbc.updatesMatching("UPDATE interview_packages")).hasSize(2);
    }

    @Test
    void packageExpiryWithNoCandidatesReturnsZeroWithoutWrites() {
        PackageRetentionJdbcTemplate jdbc = new PackageRetentionJdbcTemplate(
                List.of(), List.of(), Map.of(), Map.of());
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc,
                immediateTransactions(),
                mock(SessionDeletionCoordinator.class));

        int expired = invokeIntMethod(scheduler, "expireJobInterviewPackages");

        assertThat(expired).isZero();
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void packageContentRetentionMatchesIndexAndErasesStageBeforePackageContent() {
        UUID packageId = UUID.randomUUID();
        PackageRetentionJdbcTemplate jdbc = new PackageRetentionJdbcTemplate(
                List.of(), List.of(packageId), Map.of(), Map.of(packageId, 1));
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc,
                immediateTransactions(),
                mock(SessionDeletionCoordinator.class));

        int erased = invokeIntMethod(scheduler, "eraseExpiredPackageContent");

        assertThat(erased).isEqualTo(1);
        SqlCall packageSelection = jdbc.queryContaining("content_erased_at IS NULL");
        assertThat(packageSelection.sql())
                .contains("FROM interview_packages")
                .contains("status IN ('COMPLETED', 'EXPIRED', 'CANCELLED')")
                .contains("content_erased_at IS NULL")
                .contains("completed_at IS NOT NULL")
                .contains("completed_at <= now() - interval '90 days'")
                .contains("ORDER BY completed_at, id")
                .contains("LIMIT ?")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(packageSelection.arguments()).containsExactly(50);

        String stageUpdate = jdbc.updateContaining("context_snapshot = '{}'::jsonb").sql();
        assertThat(stageUpdate)
                .contains("UPDATE interview_package_stages")
                .contains("content_erased_at = COALESCE(content_erased_at, now())")
                .contains("version = version + 1")
                .contains("context_snapshot <> '{}'::jsonb OR content_erased_at IS NULL")
                .doesNotContain("plan_snapshot");
        String packageUpdate = jdbc.updateContaining("SET content_erased_at = now()").sql();
        assertThat(packageUpdate)
                .contains("UPDATE interview_packages")
                .contains("content_erased_at = now()")
                .contains("version = version + 1")
                .contains("content_erased_at IS NULL")
                .doesNotContain("plan_snapshot");
        assertThat(jdbc.indexOfEvent("context_snapshot = '{}'::jsonb"))
                .isLessThan(jdbc.indexOfEvent("SET content_erased_at = now()"));
    }

    @Test
    void packageContentRetentionReturnsOnlyPackagesActuallyErased() {
        UUID erasedId = UUID.randomUUID();
        UUID unchangedId = UUID.randomUUID();
        PackageRetentionJdbcTemplate jdbc = new PackageRetentionJdbcTemplate(
                List.of(),
                List.of(erasedId, unchangedId),
                Map.of(),
                Map.of(erasedId, 1, unchangedId, 0));
        DataRetentionScheduler scheduler = new DataRetentionScheduler(
                jdbc,
                immediateTransactions(),
                mock(SessionDeletionCoordinator.class));

        int erased = invokeIntMethod(scheduler, "eraseExpiredPackageContent");

        assertThat(erased).isEqualTo(1);
        assertThat(jdbc.updatesMatching("context_snapshot = '{}'::jsonb")).hasSize(2);
        assertThat(jdbc.updatesMatching("SET content_erased_at = now()")).hasSize(2);
    }

    @Test
    void retentionNeverAccessesRemovedPrivateMaterialStorage() {
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
                .contains("SELECT 1 FROM sessions session_row")
                .contains("FROM ai_jobs job")
                .doesNotContain("FROM materials", "UPDATE materials", "material_id", "material_snapshot",
                        "resume_text", "job_requirements", "profile_summary");
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

    private static final class PackageRetentionJdbcTemplate extends JdbcTemplate {
        private final List<UUID> expiringPackages;
        private final List<UUID> packagesWithExpiredContent;
        private final Map<UUID, Integer> expiryResults;
        private final Map<UUID, Integer> erasureResults;
        private final List<SqlCall> queries = new ArrayList<>();
        private final List<SqlCall> updates = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        private PackageRetentionJdbcTemplate(
                List<UUID> expiringPackages,
                List<UUID> packagesWithExpiredContent,
                Map<UUID, Integer> expiryResults,
                Map<UUID, Integer> erasureResults) {
            this.expiringPackages = expiringPackages;
            this.packagesWithExpiredContent = packagesWithExpiredContent;
            this.expiryResults = new LinkedHashMap<>(expiryResults);
            this.erasureResults = new LinkedHashMap<>(erasureResults);
        }

        @Override
        public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            record(queries, "query", sql, args);
            if (sql.contains("FROM interview_packages") && sql.contains("status = 'ACTIVE'")) {
                return expiringPackages.stream()
                        .map(id -> RetentionQueryJdbcTemplate.map(mapper, id))
                        .toList();
            }
            if (sql.contains("FROM interview_packages") && sql.contains("content_erased_at IS NULL")) {
                return packagesWithExpiredContent.stream()
                        .map(id -> RetentionQueryJdbcTemplate.map(mapper, id))
                        .toList();
            }
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            record(updates, "update", sql, args);
            if (sql.contains("UPDATE interview_packages") && sql.contains("status = 'EXPIRED'")) {
                return expiryResults.getOrDefault((UUID) args[0], 0);
            }
            if (sql.contains("UPDATE interview_packages") && sql.contains("SET content_erased_at = now()")) {
                return erasureResults.getOrDefault((UUID) args[0], 0);
            }
            if (sql.contains("UPDATE interview_package_stages")) {
                return 1;
            }
            return 0;
        }

        @Override
        public int update(String sql) {
            record(updates, "update", sql);
            return 0;
        }

        private void record(List<SqlCall> calls, String operation, String sql, Object... args) {
            calls.add(new SqlCall(sql, List.copyOf(Arrays.asList(args))));
            events.add(operation + ":" + sql);
        }

        private SqlCall queryContaining(String fragment) {
            return queries.stream()
                    .filter(call -> call.sql().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing query SQL containing: " + fragment));
        }

        private SqlCall updateContaining(String fragment) {
            return updates.stream()
                    .filter(call -> call.sql().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing update SQL containing: " + fragment));
        }

        private List<SqlCall> updatesMatching(String fragment) {
            return updates.stream().filter(call -> call.sql().contains(fragment)).toList();
        }

        private int indexOfEvent(String fragment) {
            for (int index = 0; index < events.size(); index++) {
                if (events.get(index).contains(fragment)) {
                    return index;
                }
            }
            throw new AssertionError("Missing SQL event containing: " + fragment);
        }
    }

    private record SqlCall(String sql, List<Object> arguments) {
    }

    private static int invokeIntMethod(DataRetentionScheduler scheduler, String methodName) {
        try {
            Method method = DataRetentionScheduler.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (int) method.invoke(scheduler);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Missing or failed maintenance method: " + methodName, exception);
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
