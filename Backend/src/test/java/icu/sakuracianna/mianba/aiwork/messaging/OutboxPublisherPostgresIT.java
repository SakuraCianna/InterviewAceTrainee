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

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    void migrateDedicatedDatabase() {
        String databaseUrl = requiredEnvironment("MIANBA_IT_DATABASE_URL");
        if (!ALLOWED_DATABASE_URL.equals(databaseUrl)) {
            throw new IllegalStateException("Integration test database URL is not the dedicated local database");
        }
        DataSource dataSource = new DriverManagerDataSource(
                databaseUrl,
                requiredEnvironment("MIANBA_IT_DATABASE_USERNAME"),
                requiredEnvironment("MIANBA_IT_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
