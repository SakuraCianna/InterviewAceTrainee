package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import icu.sakuracianna.mianba.aiwork.messaging.AiJobEnvelope;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyAuditService;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实 PostgreSQL 最小权限角色验证 Worker 的完整首轮消费。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiJobWorkerPostgresIT {
    private static final String ALLOWED_DATABASE_NAME = "mianba_integration_test";
    private static final String ALLOWED_DATABASE_USERNAME = "postgres";

    private final String workerTestRole =
            "mianba_worker_it_" + UUID.randomUUID().toString().replace("-", "");
    private final String workerTestPassword =
            UUID.randomUUID().toString() + UUID.randomUUID().toString();
    private JdbcTemplate ownerJdbc;
    private JdbcTemplate workerJdbc;
    private DataSource workerDataSource;
    private boolean workerTestRoleCreated;

    @BeforeAll
    void migrateDedicatedDatabaseAndCreateWorkerMember() {
        String databaseUrl = requiredEnvironment("MIANBA_IT_DATABASE_URL");
        verifyDedicatedUrl(databaseUrl);
        String databaseUsername = requiredEnvironment("MIANBA_IT_DATABASE_USERNAME");
        if (!ALLOWED_DATABASE_USERNAME.equals(databaseUsername)) {
            throw new IllegalStateException("Integration test database user must be postgres");
        }
        DataSource ownerDataSource = new DriverManagerDataSource(
                databaseUrl,
                databaseUsername,
                requiredEnvironment("MIANBA_IT_DATABASE_PASSWORD"));
        ownerJdbc = new JdbcTemplate(ownerDataSource);
        verifyDedicatedConnection(ownerJdbc);
        prepareMigrationRoles(ownerJdbc);
        verifyMigrationRolePrivileges(ownerJdbc);
        Flyway.configure().dataSource(ownerDataSource).load().migrate();
        prepareWorkerMember();

        workerDataSource = new DriverManagerDataSource(
                databaseUrl, workerTestRole, workerTestPassword);
        workerJdbc = new JdbcTemplate(workerDataSource);
        assertThat(workerJdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo(workerTestRole);
    }

    @AfterAll
    void removeWorkerMember() {
        if (ownerJdbc == null || !workerTestRoleCreated) {
            return;
        }
        ownerJdbc.execute("REVOKE mianba_worker FROM " + workerTestRole);
        ownerJdbc.execute("DROP ROLE " + workerTestRole);
        workerTestRoleCreated = false;
    }

    @Test
    void workerRoleCompletesFirstTurnAndAcknowledgesMessage() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        AiJobEnvelope envelope = AiJobEnvelope.create(
                jobId, "worker-postgres-it", "worker-postgres-it", Instant.now());
        try {
            createQueuedFixture(userId, sessionId, turnId, jobId, 5);
            Channel channel = consume(envelope);

            assertThat(ownerJdbc.queryForMap("""
                    SELECT status, stage, progress, attempt
                    FROM ai_jobs WHERE id = ?
                    """, jobId))
                    .containsAllEntriesOf(Map.of(
                            "status", "SUCCEEDED",
                            "stage", "COMPLETED",
                            "progress", 100,
                            "attempt", 1));
            assertThat(ownerJdbc.queryForMap("""
                    SELECT status, current_turn_index
                    FROM sessions WHERE id = ?
                    """, sessionId))
                    .containsAllEntriesOf(Map.of(
                            "status", "active",
                            "current_turn_index", 1));
            assertThat(ownerJdbc.queryForObject("""
                    SELECT count(*) FROM processed_messages
                    WHERE message_id = ? AND job_id = ?
                    """, Integer.class, envelope.messageId(), jobId)).isEqualTo(1);
            verify(channel).basicAck(41L, false);
        } finally {
            deleteFixture(userId, sessionId, jobId);
        }
    }

    @Test
    void workerRoleCompletesFinalTurnAndPersistsSessionReport() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        AiJobEnvelope envelope = AiJobEnvelope.create(
                jobId, "worker-report-it", "worker-report-it", Instant.now());
        try {
            createQueuedFixture(userId, sessionId, turnId, jobId, 1);

            Channel channel = consume(envelope);

            assertThat(ownerJdbc.queryForMap("""
                    SELECT status, stage, progress
                    FROM ai_jobs WHERE id = ?
                    """, jobId)).containsAllEntriesOf(Map.of(
                            "status", "SUCCEEDED",
                            "stage", "COMPLETED",
                            "progress", 100));
            assertThat(ownerJdbc.queryForMap("""
                    SELECT status, current_turn_index
                    FROM sessions WHERE id = ?
                    """, sessionId)).containsAllEntriesOf(Map.of(
                            "status", "completed",
                            "current_turn_index", 0));
            assertThat(ownerJdbc.queryForObject(
                    "SELECT count(*) FROM reports WHERE session_id = ?",
                    Integer.class,
                    sessionId)).isEqualTo(1);
            verify(channel).basicAck(41L, false);
        } finally {
            deleteFixture(userId, sessionId, jobId);
        }
    }

    private Channel consume(AiJobEnvelope envelope) throws Exception {
        ObjectMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(workerDataSource));
        ContentSafetyAuditService safety = new ContentSafetyAuditService(
                workerJdbc,
                mapper,
                new ContentSafetyProperties(
                        "worker-integration-audit-secret-0123456789-abcdefghijklmnop"));
        AiJobWorker worker = new AiJobWorker(
                workerJdbc,
                transactions,
                mapper,
                new DeterministicInterviewAiGenerator(),
                safety,
                Clock.systemUTC());
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(41L);
        properties.setMessageId(envelope.messageId().toString());
        Message message = new Message(mapper.writeValueAsBytes(envelope), properties);
        worker.consume(message, channel);
        return channel;
    }

    private void createQueuedFixture(
            UUID userId, UUID sessionId, UUID turnId, UUID jobId, int totalTurns) {
        ownerJdbc.update("INSERT INTO users(id, email) VALUES (?, ?)",
                userId, userId + "@example.invalid");
        ownerJdbc.update("""
                INSERT INTO sessions(
                    id, user_id, start_idempotency_key, interview_type, status,
                    current_turn_index, total_turns, expires_at)
                VALUES (?, ?, ?, 'civil_service', 'awaiting_ai', 0, ?, now() + interval '1 hour')
                """, sessionId, userId, "worker-postgres-it-" + sessionId, totalTurns);
        ownerJdbc.update("""
                INSERT INTO turns(
                    id, session_id, turn_index, round_name, question_text,
                    answer_text, answer_idempotency_key, status, answered_at)
                VALUES (?, ?, 0, '结构化模拟', '请分析公共服务中的效率与公平。',
                        '我会先定义目标，再列出约束和执行步骤。', ?, 'processing', now())
                """, turnId, sessionId, "worker-postgres-it-" + turnId);
        ownerJdbc.update("""
                INSERT INTO ai_jobs(
                    id, owner_id, session_id, kind, status, stage, progress,
                    attempt, max_attempts, idempotency_key, request_hash, expires_at)
                VALUES (?, ?, ?, 'GENERATE_FOLLOW_UP', 'QUEUED', 'WAITING_FOR_WORKER', 0,
                        0, 3, ?, ?, now() + interval '1 hour')
                """, jobId, userId, sessionId,
                "worker-postgres-it-" + jobId, "a".repeat(64));
    }

    private void deleteFixture(UUID userId, UUID sessionId, UUID jobId) {
        ownerJdbc.update("DELETE FROM processed_messages WHERE job_id = ?", jobId);
        ownerJdbc.update("DELETE FROM ai_call_logs WHERE job_id = ?", jobId);
        ownerJdbc.update("DELETE FROM content_safety WHERE job_id = ?", jobId);
        ownerJdbc.update("DELETE FROM report_revisions WHERE report_id IN ("
                + "SELECT id FROM reports WHERE session_id = ?)", sessionId);
        ownerJdbc.update("DELETE FROM reports WHERE session_id = ?", sessionId);
        ownerJdbc.update("DELETE FROM ai_jobs WHERE id = ?", jobId);
        ownerJdbc.update("DELETE FROM turns WHERE session_id = ?", sessionId);
        ownerJdbc.update("DELETE FROM sessions WHERE id = ?", sessionId);
        ownerJdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private static void verifyDedicatedUrl(String databaseUrl) {
        if (!databaseUrl.startsWith("jdbc:")) {
            throw new IllegalStateException("Integration test database URL must be JDBC PostgreSQL");
        }
        URI uri = URI.create(databaseUrl.substring("jdbc:".length()));
        if (!"postgresql".equals(uri.getScheme())
                || !"127.0.0.1".equals(uri.getHost())
                || !('/' + ALLOWED_DATABASE_NAME).equals(uri.getPath())
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getPort() < 1) {
            throw new IllegalStateException("Integration test URL is not the dedicated local database");
        }
    }

    private static void verifyDedicatedConnection(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(ALLOWED_DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo(ALLOWED_DATABASE_USERNAME);
    }

    private static void prepareMigrationRoles(JdbcTemplate jdbc) {
        jdbc.execute("""
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

    private static void verifyMigrationRolePrivileges(JdbcTemplate jdbc) {
        Integer unsafeRoleCount = jdbc.queryForObject("""
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

    private void prepareWorkerMember() {
        ownerJdbc.execute("CREATE ROLE " + workerTestRole
                + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '"
                + workerTestPassword + "'");
        workerTestRoleCreated = true;
        ownerJdbc.execute("GRANT mianba_worker TO " + workerTestRole);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required integration test environment is missing: " + name);
        }
        return value;
    }
}
