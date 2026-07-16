package icu.sakuracianna.mianba.aiwork.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用真实 PostgreSQL 约束验证 Outbox 重试任务的状态转换。
 *
 * 该测试仅由 Maven integration profile 执行，并且只允许连接本机专用测试库，避免误操作开发或生产数据。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxPublisherPostgresIT {
    private static final String ALLOWED_DATABASE_URL =
            "jdbc:postgresql://127.0.0.1:5432/mianba_integration_test";
    private static final String ALLOWED_DATABASE_NAME = "mianba_integration_test";
    private static final String ALLOWED_DATABASE_USERNAME = "postgres";

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    void migrateDedicatedDatabase() {
        String databaseUrl = requiredEnvironment("MIANBA_IT_DATABASE_URL");
        if (!ALLOWED_DATABASE_URL.equals(databaseUrl)) {
            throw new IllegalStateException("Integration test database URL is not the dedicated local database");
        }
        String databaseUsername = requiredEnvironment("MIANBA_IT_DATABASE_USERNAME");
        if (!ALLOWED_DATABASE_USERNAME.equals(databaseUsername)) {
            throw new IllegalStateException("Integration test database user must be the dedicated PostgreSQL owner");
        }
        DataSource dataSource = new DriverManagerDataSource(
                databaseUrl,
                databaseUsername,
                requiredEnvironment("MIANBA_IT_DATABASE_PASSWORD"));
        JdbcTemplate migrationJdbc = new JdbcTemplate(dataSource);
        verifyDedicatedDatabase(migrationJdbc);
        prepareMigrationRoles(migrationJdbc);
        verifyMigrationRolePrivileges(migrationJdbc);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * 从数据库连接本身复核测试库身份，防止仅依赖连接字符串判断而误执行迁移。
     *
     * @param migrationJdbc 具备迁移权限的测试库访问器
     */
    private static void verifyDedicatedDatabase(JdbcTemplate migrationJdbc) {
        String databaseName = migrationJdbc.queryForObject("SELECT current_database()", String.class);
        String databaseUsername = migrationJdbc.queryForObject("SELECT current_user", String.class);
        if (!ALLOWED_DATABASE_NAME.equals(databaseName)
                || !ALLOWED_DATABASE_USERNAME.equals(databaseUsername)) {
            throw new IllegalStateException("Integration test connection is not the dedicated PostgreSQL database");
        }
    }

    /**
     * 复现生产数据库初始化顺序，为完整迁移中的最小权限授权准备测试角色。
     *
     * 测试角色禁止登录且不持有管理权限，只用于验证迁移脚本能够为 API 和 Worker 分别授权。
     *
     * @param migrationJdbc 具备创建角色权限的测试库访问器
     */
    private static void prepareMigrationRoles(JdbcTemplate migrationJdbc) {
        migrationJdbc.execute("""
                DO $migration_roles$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mianba_api') THEN
                        CREATE ROLE mianba_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mianba_worker') THEN
                        CREATE ROLE mianba_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
                    END IF;
                END
                $migration_roles$
                """);
    }

    /**
     * 校验测试角色没有登录或集群管理能力，避免复用同名高权限角色执行迁移。
     *
     * @param migrationJdbc 具备查询角色属性权限的测试库访问器
     */
    private static void verifyMigrationRolePrivileges(JdbcTemplate migrationJdbc) {
        Integer unsafeRoleCount = migrationJdbc.queryForObject("""
                SELECT count(*)
                FROM pg_roles
                WHERE rolname IN ('mianba_api', 'mianba_worker')
                  AND (
                      rolcanlogin
                      OR rolsuper
                      OR rolcreatedb
                      OR rolcreaterole
                      OR rolreplication
                      OR rolbypassrls
                  )
                """, Integer.class);
        if (unsafeRoleCount == null || unsafeRoleCount != 0) {
            throw new IllegalStateException("Integration test migration roles have unsafe privileges");
        }
    }

    @Test
    void retryingJobReturnsToQueuedWithoutViolatingTheDatabaseStateConstraint() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users(id, email) VALUES (?, ?)", userId, userId + "@example.invalid");
            jdbc.update("""
                    INSERT INTO ai_jobs(
                        id, owner_id, kind, status, stage, attempt, max_attempts,
                        idempotency_key, request_hash, error_code, error_message,
                        next_attempt_at, expires_at)
                    VALUES (?, ?, 'GENERATE_REPORT', 'RETRYING', 'RETRY_BACKOFF', 1, 3,
                            ?, ?, 'AI_PROVIDER_TIMEOUT', 'temporary provider timeout',
                            now() - interval '1 second', now() + interval '1 hour')
                    """, jobId, userId, "integration-" + jobId, "a".repeat(64));
            jdbc.update("""
                    INSERT INTO outbox_events(
                        id, aggregate_type, aggregate_id, aggregate_version,
                        event_type, payload, correlation_id)
                    VALUES (?, 'AI_JOB', ?, 1, 'AI_JOB_RETRY_READY', '{}'::jsonb, ?)
                    """, eventId, jobId, "integration-" + eventId);

            OutboxPublisher publisher = new OutboxPublisher(
                    jdbc,
                    mock(RabbitTemplate.class),
                    transactions.getTransactionManager());
            Object claimed = transactions.execute(
                    status -> ReflectionTestUtils.invokeMethod(publisher, "claimOne"));

            assertThat(claimed).isNotNull();
            Map<String, Object> state = jdbc.queryForMap("""
                    SELECT status, stage, error_code, error_message, next_attempt_at, version
                    FROM ai_jobs
                    WHERE id = ?
                    """, jobId);
            assertThat(state)
                    .containsEntry("status", "QUEUED")
                    .containsEntry("stage", "WAITING_FOR_WORKER")
                    .containsEntry("version", 1L);
            assertThat(state.get("error_code")).isNull();
            assertThat(state.get("error_message")).isNull();
            assertThat(state.get("next_attempt_at")).isNull();
        } finally {
            jdbc.update("DELETE FROM outbox_events WHERE id = ?", eventId);
            jdbc.update("DELETE FROM ai_jobs WHERE id = ?", jobId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required integration test environment is missing: " + name);
        }
        return value;
    }
}
