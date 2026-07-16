package icu.sakuracianna.mianba.interview.packageflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 使用本机专用 PostgreSQL 测试库验证工作面试套餐的数据库约束和最小权限授权。
 *
 * 该测试仅由 Maven integration profile 执行。连接字符串、数据库身份和迁移角色都会被复核，避免迁移
 * 或测试写入开发及生产数据库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobInterviewPackagePostgresIT {
    private static final String ALLOWED_DATABASE_URL =
            "jdbc:postgresql://127.0.0.1:5432/mianba_integration_test";
    private static final String ALLOWED_DATABASE_NAME = "mianba_integration_test";
    private static final String ALLOWED_DATABASE_USERNAME = "postgres";

    private JdbcTemplate jdbc;
    private DataSource migrationDataSource;

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
        migrationDataSource = dataSource;
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void billingAllowsExactlyCreditsVoucherOrAdminUnlimited() {
        UUID userId = UUID.randomUUID();
        UUID voucherId = UUID.randomUUID();
        UUID creditPackageId = UUID.randomUUID();
        UUID voucherPackageId = UUID.randomUUID();
        UUID adminPackageId = UUID.randomUUID();
        try {
            insertUser(userId);
            jdbc.update("""
                    INSERT INTO vouchers(id, user_id, issue_idempotency_key, issue_reason)
                    VALUES (?, ?, ?, 'integration test')
                    """, voucherId, userId, "voucher-" + voucherId);

            assertThatThrownBy(() -> insertPackage(
                    UUID.randomUUID(), userId, null, 0, false,
                    "missing-voucher", "COMPLETED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertPackage(
                    UUID.randomUUID(), userId, voucherId, 3, false,
                    "credits-with-voucher", "COMPLETED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertPackage(
                    UUID.randomUUID(), userId, voucherId, 0, true,
                    "admin-with-voucher", "COMPLETED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertPackage(
                    UUID.randomUUID(), userId, null, 3, true,
                    "admin-with-credits", "COMPLETED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertPackage(
                    UUID.randomUUID(), userId, voucherId, 3, true,
                    "admin-with-credits-and-voucher", "COMPLETED"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            insertPackage(
                    creditPackageId, userId, null, 3, false,
                    "valid-credits", "COMPLETED");
            insertPackage(
                    voucherPackageId, userId, voucherId, 0, false,
                    "valid-voucher", "COMPLETED");
            insertPackage(
                    adminPackageId, userId, null, 0, true,
                    "valid-admin-unlimited", "COMPLETED");

            Integer packageCount = jdbc.queryForObject(
                    "SELECT count(*) FROM interview_packages WHERE user_id = ?", Integer.class, userId);
            assertThat(packageCount).isEqualTo(3);
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void userCannotHaveTwoActivePackages() {
        UUID userId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertPackage(UUID.randomUUID(), userId, null, 3, "first-active", "ACTIVE");

            assertThatThrownBy(() -> insertPackage(
                    UUID.randomUUID(), userId, null, 3, "second-active", "ACTIVE"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void packageCannotRepeatStageCodeOrSequence() {
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertPackage(packageId, userId, null, 3, "stage-uniqueness", "ACTIVE");
            insertStage(packageId, "TECHNICAL_FIRST", 1, 8, 12, 50);

            assertThatThrownBy(() -> insertStage(
                    packageId, "TECHNICAL_FIRST", 1, 8, 12, 50))
                    .isInstanceOf(DataIntegrityViolationException.class);

            List<String> uniqueConstraints = jdbc.queryForList("""
                    SELECT pg_get_constraintdef(oid)
                    FROM pg_constraint
                    WHERE conrelid = 'interview_package_stages'::regclass
                      AND contype = 'u'
                    """, String.class);
            assertThat(uniqueConstraints)
                    .contains("UNIQUE (package_id, stage_code)")
                    .contains("UNIQUE (package_id, sequence_no)");
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void stageRejectsMismatchedCodeAndSequence() {
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertPackage(packageId, userId, null, 3, "stage-order", "ACTIVE");

            assertThatThrownBy(() -> insertStage(
                    packageId, "TECHNICAL_FIRST", 2, 8, 12, 50))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertStage(
                    packageId, "TECHNICAL_SECOND", 3, 7, 12, 60))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertStage(
                    packageId, "HR_FINAL", 1, 5, 8, 25))
                    .isInstanceOf(DataIntegrityViolationException.class);

            Integer stageCount = jdbc.queryForObject(
                    "SELECT count(*) FROM interview_package_stages WHERE package_id = ?",
                    Integer.class,
                    packageId);
            assertThat(stageCount).isZero();
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void packageRejectsAnyExpirationOtherThanExactlyThirtyDays() {
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        try {
            insertUser(userId);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO interview_packages(
                        id, user_id, start_idempotency_key, request_hash, status,
                        current_stage_code, charged_credit, plan_version, rubric_version,
                        material_snapshot, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'COMPLETED', 'TECHNICAL_FIRST', 3,
                            'job-cn-v1', 'job-rubric-v1', '{}'::jsonb,
                            timestamptz '2026-01-30 00:00:00+00',
                            timestamptz '2026-01-01 00:00:00+00',
                            timestamptz '2026-01-01 00:00:00+00')
                    """, packageId, userId, "twenty-nine-days", "0".repeat(64)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            Integer packageCount = jdbc.queryForObject(
                    "SELECT count(*) FROM interview_packages WHERE id = ?", Integer.class, packageId);
            assertThat(packageCount).isZero();
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void stageRejectsInvalidTurnAndMinuteRanges() {
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertPackage(packageId, userId, null, 3, "stage-ranges", "ACTIVE");

            assertThatThrownBy(() -> insertStage(
                    packageId, "TECHNICAL_FIRST", 1, 0, 12, 50))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertStage(
                    packageId, "TECHNICAL_FIRST", 1, 12, 8, 50))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertStage(
                    packageId, "TECHNICAL_FIRST", 1, 8, 31, 50))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertStage(
                    packageId, "TECHNICAL_FIRST", 1, 8, 12, 0))
                    .isInstanceOf(DataIntegrityViolationException.class);

            Integer stageCount = jdbc.queryForObject(
                    "SELECT count(*) FROM interview_package_stages WHERE package_id = ?",
                    Integer.class,
                    packageId);
            assertThat(stageCount).isZero();
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void packageTablesGrantOnlyRequiredApiAndWorkerPrivileges() {
        for (String table : List.of("interview_packages", "interview_package_stages")) {
            assertPrivileges(
                    "mianba_api",
                    table,
                    List.of("SELECT", "INSERT", "UPDATE"),
                    List.of("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
            assertPrivileges(
                    "mianba_worker",
                    table,
                    List.of("SELECT", "UPDATE"),
                    List.of("INSERT", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
        }
    }

    @Test
    void reportsRequireExactlyOneUniqueSubject() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertCompletedSession(sessionId, userId);
            insertPackage(packageId, userId, null, 3, "report-subject", "COMPLETED");

            insertReport(UUID.randomUUID(), sessionId, null, "SESSION");
            assertThatThrownBy(() -> insertReport(
                    UUID.randomUUID(), sessionId, null, "SESSION"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            insertReport(UUID.randomUUID(), null, packageId, "PACKAGE");
            assertThatThrownBy(() -> insertReport(
                    UUID.randomUUID(), null, packageId, "PACKAGE"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertReport(
                    UUID.randomUUID(), null, null, "SESSION"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertReport(
                    UUID.randomUUID(), sessionId, packageId, "SESSION"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertReport(
                    UUID.randomUUID(), sessionId, null, "PACKAGE"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void reportRevisionsAreUniqueAndRequireObjectJson() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertCompletedSession(sessionId, userId);
            insertReport(reportId, sessionId, null, "SESSION");
            jdbc.update("""
                    INSERT INTO report_revisions(report_id, revision_no, source, report_json)
                    VALUES (?, 1, 'TEMPLATE', '{}'::jsonb)
                    """, reportId);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO report_revisions(report_id, revision_no, source, report_json)
                    VALUES (?, 1, 'AI_ENHANCEMENT', '{}'::jsonb)
                    """, reportId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO report_revisions(report_id, revision_no, source, report_json)
                    VALUES (?, 2, 'AI_ENHANCEMENT', '[]'::jsonb)
                    """, reportId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void structuredReportTablesGrantOnlyRuntimePrivileges() {
        assertPrivileges(
                "mianba_api",
                "report_revisions",
                List.of("SELECT"),
                List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
        assertPrivileges(
                "mianba_api",
                "turn_dimension_scores",
                List.of("SELECT", "DELETE"),
                List.of("INSERT", "UPDATE", "TRUNCATE", "REFERENCES", "TRIGGER"));
        assertPrivileges(
                "mianba_worker",
                "report_revisions",
                List.of("SELECT", "INSERT"),
                List.of("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
        assertPrivileges(
                "mianba_worker",
                "turn_dimension_scores",
                List.of("SELECT", "INSERT", "UPDATE"),
                List.of("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
    }

    @Test
    void turnEvaluationArraysRejectInvalidShapesCodesAndCardinality() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertCompletedSession(sessionId, userId);
            jdbc.update("""
                    INSERT INTO turns(id, session_id, turn_index, round_name, question_text)
                    VALUES (?, ?, 0, '一面', '请介绍自己')
                    """, turnId, sessionId);

            jdbc.update("""
                    UPDATE turns
                    SET covered_sections = ?::jsonb,
                        covered_topics = ?::jsonb,
                        risk_flags = ?::jsonb
                    WHERE id = ?
                    """,
                    "[\"INTRODUCTION\",\"FOUNDATIONS\"]",
                    "[\"JAVA_MEMORY_MODEL\"]",
                    "[\"EVIDENCE_MISSING\"]",
                    turnId);
            assertThat(jdbc.queryForList("""
                    SELECT jsonb_array_elements_text(covered_sections)
                    FROM turns WHERE id = ?
                    """, String.class, turnId))
                    .containsExactly("INTRODUCTION", "FOUNDATIONS");

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE turns SET covered_sections = '{}'::jsonb WHERE id = ?", turnId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE turns SET covered_topics = '[\"VALID\",1]'::jsonb WHERE id = ?", turnId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE turns SET risk_flags = '[\"lower_case\"]'::jsonb WHERE id = ?", turnId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE turns SET risk_flags = ?::jsonb WHERE id = ?",
                    "[\"" + "A".repeat(65) + "\"]",
                    turnId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            String tooManyCodes = IntStream.range(0, 17)
                    .mapToObj(index -> "\"CODE_" + index + "\"")
                    .collect(Collectors.joining(",", "[", "]"));
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE turns SET covered_sections = ?::jsonb WHERE id = ?",
                    tooManyCodes,
                    turnId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            deleteUserFixtures(userId);
        }
    }

    @Test
    void v4GrantsOnlyTheNewRuntimePrivileges() {
        assertPrivileges(
                "mianba_worker",
                "ai_jobs",
                List.of("SELECT", "INSERT", "UPDATE"),
                List.of("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
        assertThat(hasTablePrivilege("mianba_api", "turn_dimension_scores", "DELETE")).isTrue();
        assertThat(hasTablePrivilege("mianba_worker", "turn_dimension_scores", "DELETE")).isFalse();
    }

    @Test
    void upgradesExistingV2SessionReportWithoutDataLoss() {
        String schemaName = "mianba_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String historyTable = "flyway_history_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String quotedSchema = quoteIsolatedSchema(schemaName);
        JdbcTemplate upgradeJdbc = new JdbcTemplate(migrationDataSource);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        try {
            isolatedFlyway(schemaName, historyTable, "2").migrate();
            upgradeJdbc.update("""
                    INSERT INTO %s.users(id, email)
                    VALUES (?, ?)
                    """.formatted(quotedSchema), userId, userId + "@example.invalid");
            upgradeJdbc.update("""
                    INSERT INTO %s.sessions(
                        id, user_id, start_idempotency_key, interview_type, status,
                        total_turns, ended_at)
                    VALUES (?, ?, ?, 'job', 'completed', 1, now())
                    """.formatted(quotedSchema), sessionId, userId, "upgrade-session-" + sessionId);
            upgradeJdbc.update("""
                    INSERT INTO %s.reports(
                        id, session_id, total_score, report_json, schema_version)
                    VALUES (?, ?, 77, '{"summary":"legacy"}'::jsonb, 3)
                    """.formatted(quotedSchema), reportId, sessionId);

            isolatedFlyway(schemaName, historyTable, null).migrate();

            MigratedReport migratedReport = upgradeJdbc.queryForObject("""
                    SELECT session_id, package_id, total_score, schema_version,
                           report_json ->> 'summary' AS summary,
                           report_scope, generation_status, current_revision,
                           summary_source, prompt_version, rubric_version,
                           enhanced_at, enhancement_error_code
                    FROM %s.reports
                    WHERE id = ?
                    """.formatted(quotedSchema), (resultSet, rowNumber) -> new MigratedReport(
                            resultSet.getObject("session_id", UUID.class),
                            resultSet.getObject("package_id", UUID.class),
                            resultSet.getInt("total_score"),
                            resultSet.getInt("schema_version"),
                            resultSet.getString("summary"),
                            resultSet.getString("report_scope"),
                            resultSet.getString("generation_status"),
                            resultSet.getInt("current_revision"),
                            resultSet.getString("summary_source"),
                            resultSet.getString("prompt_version"),
                            resultSet.getString("rubric_version"),
                            resultSet.getObject("enhanced_at"),
                            resultSet.getString("enhancement_error_code")), reportId);
            assertThat(migratedReport)
                    .isEqualTo(new MigratedReport(
                            sessionId,
                            null,
                            77,
                            3,
                            "legacy",
                            "SESSION",
                            "BASE_READY",
                            1,
                            "TEMPLATE",
                            "legacy-unknown",
                            "legacy-unknown",
                            null,
                            null));

            MigratedRevision migratedRevision = upgradeJdbc.queryForObject("""
                    SELECT revision_no, source, report_json ->> 'summary' AS summary,
                           prompt_version, rubric_version, output_schema_version
                    FROM %s.report_revisions
                    WHERE report_id = ?
                    """.formatted(quotedSchema), (resultSet, rowNumber) -> new MigratedRevision(
                            resultSet.getInt("revision_no"),
                            resultSet.getString("source"),
                            resultSet.getString("summary"),
                            resultSet.getString("prompt_version"),
                            resultSet.getString("rubric_version"),
                            resultSet.getString("output_schema_version")), reportId);
            assertThat(migratedRevision)
                    .isEqualTo(new MigratedRevision(
                            1,
                            "TEMPLATE",
                            "legacy",
                            "legacy-unknown",
                            "legacy-unknown",
                            "3"));

            Integer legacyUniqueCount = upgradeJdbc.queryForObject("""
                    SELECT count(*)
                    FROM pg_constraint constraint_row
                    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                    JOIN pg_namespace namespace_row ON namespace_row.oid = table_row.relnamespace
                    WHERE namespace_row.nspname = ?
                      AND table_row.relname = 'reports'
                      AND constraint_row.contype = 'u'
                      AND pg_get_constraintdef(constraint_row.oid) = 'UNIQUE (session_id)'
                    """, Integer.class, schemaName);
            assertThat(legacyUniqueCount).isZero();

            List<String> reportIndexes = upgradeJdbc.queryForList("""
                    SELECT indexdef
                    FROM pg_indexes
                    WHERE schemaname = ? AND tablename = 'reports'
                      AND indexname IN ('ux_reports_session', 'ux_reports_package')
                    """, String.class, schemaName);
            assertThat(reportIndexes)
                    .anySatisfy(indexDefinition -> assertThat(indexDefinition)
                            .contains("UNIQUE INDEX ux_reports_session")
                            .contains("WHERE (session_id IS NOT NULL)"))
                    .anySatisfy(indexDefinition -> assertThat(indexDefinition)
                            .contains("UNIQUE INDEX ux_reports_package")
                            .contains("WHERE (package_id IS NOT NULL)"));
        } finally {
            upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + quotedSchema + " CASCADE");
        }
    }

    @Test
    void upgradesExistingV3TurnsWithEmptyEvaluationArrays() {
        String schemaName = "mianba_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String historyTable = "flyway_history_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String quotedSchema = quoteIsolatedSchema(schemaName);
        JdbcTemplate upgradeJdbc = new JdbcTemplate(migrationDataSource);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        try {
            isolatedFlyway(schemaName, historyTable, "3").migrate();
            upgradeJdbc.update("""
                    INSERT INTO %s.users(id, email)
                    VALUES (?, ?)
                    """.formatted(quotedSchema), userId, userId + "@example.invalid");
            upgradeJdbc.update("""
                    INSERT INTO %s.sessions(
                        id, user_id, start_idempotency_key, interview_type, status, total_turns)
                    VALUES (?, ?, ?, 'job', 'active', 1)
                    """.formatted(quotedSchema), sessionId, userId, "upgrade-session-" + sessionId);
            upgradeJdbc.update("""
                    INSERT INTO %s.turns(id, session_id, turn_index, round_name, question_text)
                    VALUES (?, ?, 0, '一面', '请介绍自己')
                    """.formatted(quotedSchema), turnId, sessionId);

            isolatedFlyway(schemaName, historyTable, null).migrate();

            EvaluationArrays arrays = upgradeJdbc.queryForObject("""
                    SELECT covered_sections::text AS covered_sections,
                           covered_topics::text AS covered_topics,
                           risk_flags::text AS risk_flags
                    FROM %s.turns WHERE id = ?
                    """.formatted(quotedSchema), (resultSet, rowNumber) -> new EvaluationArrays(
                            resultSet.getString("covered_sections"),
                            resultSet.getString("covered_topics"),
                            resultSet.getString("risk_flags")), turnId);
            assertThat(arrays).isEqualTo(new EvaluationArrays("[]", "[]", "[]"));
        } finally {
            upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + quotedSchema + " CASCADE");
        }
    }

    private void insertUser(UUID userId) {
        jdbc.update("INSERT INTO users(id, email) VALUES (?, ?)", userId, userId + "@example.invalid");
    }

    private void insertCompletedSession(UUID sessionId, UUID userId) {
        jdbc.update("""
                INSERT INTO sessions(
                    id, user_id, start_idempotency_key, interview_type, status,
                    total_turns, ended_at)
                VALUES (?, ?, ?, 'job', 'completed', 1, now())
                """, sessionId, userId, "report-session-" + sessionId);
    }

    private void insertReport(UUID reportId, UUID sessionId, UUID packageId, String reportScope) {
        jdbc.update("""
                INSERT INTO reports(
                    id, session_id, package_id, report_scope, total_score, report_json)
                VALUES (?, ?, ?, ?, 80, '{}'::jsonb)
                """, reportId, sessionId, packageId, reportScope);
    }

    private void insertPackage(
            UUID packageId,
            UUID userId,
            UUID voucherId,
            int chargedCredit,
            String idempotencyKey,
            String status) {
        insertPackage(
                packageId, userId, voucherId, chargedCredit, false,
                idempotencyKey, status);
    }

    private void insertPackage(
            UUID packageId,
            UUID userId,
            UUID voucherId,
            int chargedCredit,
            boolean adminUnlimitedUsage,
            String idempotencyKey,
            String status) {
        jdbc.update("""
                INSERT INTO interview_packages(
                    id, user_id, voucher_id, start_idempotency_key, request_hash,
                    status, current_stage_code, charged_credit, admin_unlimited_usage,
                    plan_version, rubric_version, material_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, 'TECHNICAL_FIRST', ?, ?,
                        'job-cn-v1', 'job-rubric-v1', '{}'::jsonb)
                """,
                packageId,
                userId,
                voucherId,
                idempotencyKey,
                "0".repeat(64),
                status,
                chargedCredit,
                adminUnlimitedUsage);
    }

    private void insertStage(
            UUID packageId,
            String stageCode,
            int sequence,
            int minTurns,
            int maxTurns,
            int targetMinutes) {
        jdbc.update("""
                INSERT INTO interview_package_stages(
                    package_id, stage_code, sequence_no, status,
                    min_turns, max_turns, target_duration_minutes)
                VALUES (?, ?, ?, 'LOCKED', ?, ?, ?)
                """, packageId, stageCode, sequence, minTurns, maxTurns, targetMinutes);
    }

    private void assertPrivileges(
            String role, String table, List<String> allowed, List<String> denied) {
        for (String privilege : allowed) {
            assertThat(hasTablePrivilege(role, table, privilege))
                    .as("%s has %s on %s", role, privilege, table)
                    .isTrue();
        }
        for (String privilege : denied) {
            assertThat(hasTablePrivilege(role, table, privilege))
                    .as("%s does not have %s on %s", role, privilege, table)
                    .isFalse();
        }
    }

    private boolean hasTablePrivilege(String role, String table, String privilege) {
        Boolean granted = jdbc.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)",
                Boolean.class,
                role,
                "public." + table,
                privilege);
        return Boolean.TRUE.equals(granted);
    }

    private Flyway isolatedFlyway(String schemaName, String historyTable, String targetVersion) {
        if (!historyTable.matches("flyway_history_[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Unexpected integration-test history table name");
        }
        var configuration = Flyway.configure()
                .dataSource(migrationDataSource)
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .table(historyTable)
                .createSchemas(true);
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        return configuration.load();
    }

    private static String quoteIsolatedSchema(String schemaName) {
        if (!schemaName.matches("mianba_upgrade_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Unexpected integration-test schema name");
        }
        return '"' + schemaName.replace("\"", "\"\"") + '"';
    }

    private void deleteUserFixtures(UUID userId) {
        jdbc.update("DELETE FROM interview_packages WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM vouchers WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private static void verifyDedicatedDatabase(JdbcTemplate migrationJdbc) {
        String databaseName = migrationJdbc.queryForObject("SELECT current_database()", String.class);
        String databaseUsername = migrationJdbc.queryForObject("SELECT current_user", String.class);
        if (!ALLOWED_DATABASE_NAME.equals(databaseName)
                || !ALLOWED_DATABASE_USERNAME.equals(databaseUsername)) {
            throw new IllegalStateException("Integration test connection is not the dedicated PostgreSQL database");
        }
    }

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

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required integration test environment is missing: " + name);
        }
        return value;
    }

    private record MigratedReport(
            UUID sessionId,
            UUID packageId,
            int totalScore,
            int schemaVersion,
            String summary,
            String reportScope,
            String generationStatus,
            int currentRevision,
            String summarySource,
            String promptVersion,
            String rubricVersion,
            Object enhancedAt,
            String enhancementErrorCode) {
    }

    private record MigratedRevision(
            int revisionNo,
            String source,
            String summary,
            String promptVersion,
            String rubricVersion,
            String outputSchemaVersion) {
    }

    private record EvaluationArrays(
            String coveredSections,
            String coveredTopics,
            String riskFlags) {
    }
}
