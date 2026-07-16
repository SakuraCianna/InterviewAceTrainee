package icu.sakuracianna.mianba.interview.packageflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JdbcJobInterviewPackageServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void creditCreatePersistsCompleteAggregateWithSafeSnapshotsInRequiredOrder() throws Exception {
        Fixture fixture = new Fixture(BillingMode.CREDIT);
        fixture.jdbc.balance = 7;
        fixture.jdbc.profileSummary = "  核心\u0000  后端\n工程师  ";
        fixture.jdbc.jobTitle = "岗".repeat(170);
        fixture.jdbc.jobRequirements = "  Java\tSpring  " + "需".repeat(2600);
        fixture.jdbc.keywordsJson = JSON.writeValueAsString(List.of(
                " Java ", "Java", "Spring\nBoot", "超".repeat(30), " ",
                "数据库", "并发", "网络", "JVM", "消息队列", "缓存", "安全", "测试", "云原生", "性能"));

        JobInterviewPackageView view = fixture.service.create(
                fixture.userId,
                fixture.packageId,
                fixture.sessionId,
                fixture.materialId,
                "package-key");

        assertThat(view.packageId()).isEqualTo(fixture.packageId);
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.currentStageCode()).isEqualTo(JobInterviewStage.TECHNICAL_FIRST);
        assertThat(view.chargedCredit()).isEqualTo(3);
        assertThat(view.adminUnlimitedUsage()).isFalse();
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(30, ChronoUnit.DAYS));
        assertThat(view.stages())
                .extracting(JobInterviewPackageView.Stage::stageCode)
                .containsExactly(
                        JobInterviewStage.TECHNICAL_FIRST,
                        JobInterviewStage.TECHNICAL_SECOND,
                        JobInterviewStage.HR_FINAL);
        assertThat(view.stages())
                .extracting(JobInterviewPackageView.Stage::status)
                .containsExactly("IN_PROGRESS", "LOCKED", "LOCKED");
        assertThat(view.stages().getFirst().sessionId()).isEqualTo(fixture.sessionId);

        SqlCall materialQuery = fixture.jdbc.singleCallContaining("FROM materials");
        assertThat(materialQuery.sql())
                .contains("user_id = ?", "status = 'ready'", "interview_type = 'job'")
                .contains("profile_summary", "job_title", "job_requirements", "keywords")
                .doesNotContain("resume_text", "resume_filename", "resume_content_type");
        assertThat(materialQuery.args()).containsExactly(fixture.materialId, fixture.userId);

        SqlCall packageInsert = fixture.jdbc.singleCallContaining("INSERT INTO interview_packages");
        assertThat(packageInsert.args().get(0)).isEqualTo(fixture.packageId);
        assertThat(packageInsert.args().get(1)).isEqualTo(fixture.userId);
        assertThat(packageInsert.args().get(2)).isEqualTo(fixture.materialId);
        assertThat(packageInsert.args().get(3)).isNull();
        assertThat(packageInsert.args().get(6)).isEqualTo(3);
        assertThat(packageInsert.args().get(7)).isEqualTo(false);
        JsonNode materialSnapshot = JSON.readTree((String) packageInsert.args().get(8));
        assertThat(materialSnapshot.isObject()).isTrue();
        assertThat(materialSnapshot.has("resume_text")).isFalse();
        assertThat(materialSnapshot.has("resume_filename")).isFalse();
        assertThat(materialSnapshot.get("profile_summary").stringValue()).isEqualTo("核心 后端 工程师");
        assertThat(materialSnapshot.get("job_title").stringValue()).hasSize(160);
        assertThat(materialSnapshot.get("job_requirements_summary").stringValue()).hasSize(2500);
        assertThat(materialSnapshot.get("keywords")).hasSize(12);
        assertThat(materialSnapshot.get("keywords").get(0).stringValue()).isEqualTo("Java");
        assertThat(materialSnapshot.get("keywords").get(1).stringValue()).isEqualTo("Spring Boot");
        assertThat(materialSnapshot.get("keywords").get(2).stringValue()).hasSize(24);
        assertThat(packageInsert.args().get(9)).isEqualTo(Timestamp.from(NOW.plus(30, ChronoUnit.DAYS)));
        assertThat(packageInsert.args().get(10)).isEqualTo(Timestamp.from(NOW));
        assertThat(packageInsert.args().get(11)).isEqualTo(Timestamp.from(NOW));

        List<SqlCall> stageInserts = fixture.jdbc.callsContaining("INSERT INTO interview_package_stages");
        assertThat(stageInserts).hasSize(3);
        JobInterviewPlan plan = JobInterviewPlan.chineseEnterpriseV1();
        for (int index = 0; index < stageInserts.size(); index++) {
            SqlCall call = stageInserts.get(index);
            JobInterviewPlan.StagePlan stagePlan = plan.stages().get(index);
            assertThat(call.args().get(0)).isEqualTo(fixture.packageId);
            assertThat(call.args().get(1)).isEqualTo(stagePlan.code().name());
            assertThat(call.args().get(2)).isEqualTo(stagePlan.code().sequence());
            assertThat(call.args().get(3)).isEqualTo(index == 0 ? "UNLOCKED" : "LOCKED");
            JsonNode planSnapshot = JSON.readTree((String) call.args().get(4));
            assertThat(planSnapshot.get("stage_code").stringValue()).isEqualTo(stagePlan.code().name());
            assertThat(planSnapshot.get("sequence").asInt()).isEqualTo(stagePlan.code().sequence());
            assertThat(planSnapshot.get("min_turns").asInt()).isEqualTo(stagePlan.minTurns());
            assertThat(planSnapshot.get("max_turns").asInt()).isEqualTo(stagePlan.maxTurns());
            assertThat(planSnapshot.get("target_duration_minutes").asInt())
                    .isEqualTo(stagePlan.targetMinutes());
            assertThat(planSnapshot.get("required_sections"))
                    .extracting(JsonNode::stringValue)
                    .containsExactlyElementsOf(stagePlan.requiredSections());
            assertThat(planSnapshot.get("end_rule").stringValue())
                    .isEqualTo("MIN_TURNS_AND_COVERAGE_AND_MODEL_OR_MAX_TURNS");
            assertThat(call.sql()).contains("'{}'::jsonb");
            assertThat(call.args().subList(5, 8))
                    .containsExactly(stagePlan.minTurns(), stagePlan.maxTurns(), stagePlan.targetMinutes());
        }

        SqlCall balanceUpdate = fixture.jdbc.singleCallContaining("UPDATE users");
        assertThat(balanceUpdate.args()).containsExactly(3, fixture.userId);
        SqlCall ledgerInsert = fixture.jdbc.singleCallContaining("INSERT INTO credit_ledger");
        assertThat(ledgerInsert.sql())
                .contains("'interview_package_start'", "related_session_id", "related_package_id")
                .contains("NULL");
        assertThat(ledgerInsert.args())
                .containsExactly(
                        fixture.userId,
                        -3,
                        4,
                        "interview-package-start:" + fixture.packageId,
                        fixture.packageId,
                        Timestamp.from(NOW));

        SqlCall sessionInsert = fixture.jdbc.singleCallContaining("INSERT INTO sessions");
        assertThat(sessionInsert.sql())
                .contains("'job'", "'active'", "charged_credit", "voucher_id")
                .contains("0", "NULL");
        assertThat(sessionInsert.args())
                .containsExactly(
                        fixture.sessionId,
                        fixture.userId,
                        "job-package-start:" + fixture.packageId,
                        fixture.materialId,
                        12,
                        false,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW.plus(24, ChronoUnit.HOURS)),
                        Timestamp.from(NOW),
                        Timestamp.from(NOW));
        SqlCall stageBinding = fixture.jdbc.singleCallContaining("UPDATE interview_package_stages");
        assertThat(stageBinding.sql())
                .contains("status = 'IN_PROGRESS'", "stage_code = 'TECHNICAL_FIRST'")
                .contains("status = 'UNLOCKED'");
        assertThat(stageBinding.args())
                .containsExactly(
                        fixture.sessionId,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW),
                        fixture.packageId);
        SqlCall turnInsert = fixture.jdbc.singleCallContaining("INSERT INTO turns");
        assertThat(turnInsert.sql())
                .contains("turn_index", "section_code", "question_type", "topic_code", "parent_turn_id")
                .contains("'INTRODUCTION'", "'self_introduction'", "NULL");
        assertThat(turnInsert.args())
                .containsExactly(
                        fixture.sessionId,
                        "技术一面 · 自我介绍",
                        "请用两分钟介绍与你目标岗位最相关的一段经历。",
                        Timestamp.from(NOW));

        assertThat(fixture.jdbc.indexOf("INSERT INTO interview_packages"))
                .isLessThan(fixture.jdbc.indexOf("INSERT INTO interview_package_stages"));
        assertThat(fixture.jdbc.lastIndexOf("INSERT INTO interview_package_stages"))
                .isLessThan(fixture.jdbc.indexOf("UPDATE users"));
        assertThat(fixture.jdbc.indexOf("UPDATE users"))
                .isLessThan(fixture.jdbc.indexOf("INSERT INTO credit_ledger"));
        assertThat(fixture.jdbc.indexOf("INSERT INTO credit_ledger"))
                .isLessThan(fixture.jdbc.indexOf("INSERT INTO sessions"));
        assertThat(fixture.jdbc.indexOf("INSERT INTO sessions"))
                .isLessThan(fixture.jdbc.indexOf("UPDATE interview_package_stages"));
        assertThat(fixture.jdbc.indexOf("UPDATE interview_package_stages"))
                .isLessThan(fixture.jdbc.indexOf("INSERT INTO turns"));
        assertThat(fixture.jdbc.indexOf("INSERT INTO turns"))
                .isLessThan(fixture.jdbc.lastIndexOf("FROM interview_packages p"));
        List<SqlCall> replayQueries = fixture.jdbc.callsContaining("start_idempotency_key = ?");
        assertThat(replayQueries).hasSize(2);
        int firstReplayIndex = fixture.jdbc.indexOf("start_idempotency_key = ?");
        int secondReplayIndex = fixture.jdbc.indexOfAfter(
                "start_idempotency_key = ?", firstReplayIndex + 1);
        assertThat(firstReplayIndex)
                .isLessThan(fixture.jdbc.indexOf("FROM users"));
        assertThat(fixture.jdbc.indexOf("FROM users"))
                .isLessThan(secondReplayIndex);
        assertThat(secondReplayIndex)
                .isLessThan(fixture.jdbc.indexOf("FROM materials"));
        assertThat(fixture.jdbc.indexOf("FROM materials"))
                .isLessThan(fixture.jdbc.indexOf("FROM vouchers"));
    }

    @Test
    void voucherCreateRedeemsWholeVoucherWithoutBalanceOrLedgerSideEffects() {
        Fixture fixture = new Fixture(BillingMode.VOUCHER);
        fixture.jdbc.balance = 99;

        JobInterviewPackageView view = fixture.service.create(
                fixture.userId,
                fixture.packageId,
                fixture.sessionId,
                fixture.materialId,
                "voucher-key");

        assertThat(view.chargedCredit()).isZero();
        assertThat(view.adminUnlimitedUsage()).isFalse();
        SqlCall voucherQuery = fixture.jdbc.singleCallContaining("FROM vouchers");
        assertThat(voucherQuery.sql())
                .contains("FOR UPDATE SKIP LOCKED", "scope_interview_type = 'job'")
                .contains("remaining_uses > 0", "status = 'available'");
        assertThat(voucherQuery.args()).containsExactly(fixture.userId);
        SqlCall packageInsert = fixture.jdbc.singleCallContaining("INSERT INTO interview_packages");
        assertThat(packageInsert.args().get(3)).isEqualTo(fixture.jdbc.voucherId);
        assertThat(packageInsert.args().get(6)).isEqualTo(0);
        assertThat(packageInsert.args().get(7)).isEqualTo(false);
        assertThat(fixture.jdbc.callsContaining("UPDATE users")).isEmpty();
        assertThat(fixture.jdbc.callsContaining("INSERT INTO credit_ledger")).isEmpty();
        SqlCall voucherUpdate = fixture.jdbc.singleCallContaining("UPDATE vouchers");
        assertThat(voucherUpdate.sql())
                .contains("remaining_uses = 0", "status = 'redeemed'")
                .contains("redeemed_package_id = ?", "redeemed_session_id = NULL", "redeemed_at = ?");
        assertThat(voucherUpdate.args())
                .containsExactly(fixture.packageId, Timestamp.from(NOW), fixture.jdbc.voucherId);
    }

    @Test
    void adminCreateSkipsVoucherLookupAndAllBillingWrites() {
        Fixture fixture = new Fixture(BillingMode.ADMIN);

        JobInterviewPackageView view = fixture.service.create(
                fixture.userId,
                fixture.packageId,
                fixture.sessionId,
                fixture.materialId,
                "admin-key");

        assertThat(view.chargedCredit()).isZero();
        assertThat(view.adminUnlimitedUsage()).isTrue();
        SqlCall packageInsert = fixture.jdbc.singleCallContaining("INSERT INTO interview_packages");
        assertThat(packageInsert.args().get(3)).isNull();
        assertThat(packageInsert.args().get(6)).isEqualTo(0);
        assertThat(packageInsert.args().get(7)).isEqualTo(true);
        assertThat(fixture.jdbc.callsContaining("FROM vouchers")).isEmpty();
        assertThat(fixture.jdbc.callsContaining("UPDATE vouchers")).isEmpty();
        assertThat(fixture.jdbc.callsContaining("UPDATE users")).isEmpty();
        assertThat(fixture.jdbc.callsContaining("INSERT INTO credit_ledger")).isEmpty();
    }

    @Test
    void replayReturnsOriginalAggregateWithoutLockOrWritesAndRejectsChangedContent() {
        Fixture fixture = new Fixture(BillingMode.CREDIT);
        fixture.jdbc.balance = 8;
        JobInterviewPackageView created = fixture.service.create(
                fixture.userId,
                fixture.packageId,
                fixture.sessionId,
                fixture.materialId,
                "same-key");
        fixture.jdbc.clearCalls();

        JobInterviewPackageView replayed = fixture.service.create(
                fixture.userId,
                fixture.packageId,
                fixture.sessionId,
                fixture.materialId,
                "same-key");

        assertThat(replayed).isEqualTo(created);
        assertThat(fixture.jdbc.callsContaining("FOR UPDATE")).isEmpty();
        assertThat(fixture.jdbc.updateCalls()).isEmpty();
        assertThat(fixture.jdbc.calls.getFirst().sql()).contains("start_idempotency_key = ?");

        fixture.jdbc.clearCalls();
        assertThatThrownBy(() -> fixture.service.create(
                        fixture.userId,
                        fixture.packageId,
                        UUID.randomUUID(),
                        fixture.materialId,
                        "same-key"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.detail()).isEqualTo("idempotency_key_conflict");
                });
        assertThat(fixture.jdbc.updateCalls()).isEmpty();
        assertThat(fixture.jdbc.callsContaining("FOR UPDATE")).isEmpty();

        fixture.jdbc.clearCalls();
        assertThatThrownBy(() -> fixture.service.create(
                        fixture.userId,
                        UUID.randomUUID(),
                        fixture.sessionId,
                        fixture.materialId,
                        "same-key"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("idempotency_key_conflict"));
        assertThat(fixture.jdbc.updateCalls()).isEmpty();

        fixture.jdbc.clearCalls();
        assertThatThrownBy(() -> fixture.service.create(
                        fixture.userId,
                        fixture.packageId,
                        fixture.sessionId,
                        UUID.randomUUID(),
                        "same-key"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("idempotency_key_conflict"));
        assertThat(fixture.jdbc.updateCalls()).isEmpty();
    }

    @Test
    void malformedMaterialKeywordsFailClosedBeforeAnyWrite() {
        Fixture fixture = new Fixture(BillingMode.CREDIT);
        fixture.jdbc.keywordsJson = "{not-an-array}";

        assertThatThrownBy(() -> fixture.service.create(
                        fixture.userId,
                        fixture.packageId,
                        fixture.sessionId,
                        fixture.materialId,
                        "bad-keywords"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safe job interview package snapshots");
        assertThat(fixture.jdbc.updateCalls()).isEmpty();
        assertThat(fixture.jdbc.callsContaining("FROM vouchers")).isEmpty();
    }

    @Test
    void userMaterialAndBalanceErrorsFailBeforeAnyBillingWrite() {
        Fixture missingUser = new Fixture(BillingMode.CREDIT);
        missingUser.jdbc.userExists = false;
        assertThatThrownBy(() -> missingUser.service.create(
                        missingUser.userId,
                        missingUser.packageId,
                        missingUser.sessionId,
                        missingUser.materialId,
                        "missing-user"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(404);
                    assertThat(error.detail()).isEqualTo("user_not_found");
                });
        assertThat(missingUser.jdbc.updateCalls()).isEmpty();

        Fixture missingMaterial = new Fixture(BillingMode.CREDIT);
        missingMaterial.jdbc.materialExists = false;
        assertThatThrownBy(() -> missingMaterial.service.create(
                        missingMaterial.userId,
                        missingMaterial.packageId,
                        missingMaterial.sessionId,
                        missingMaterial.materialId,
                        "missing-material"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("interview_material_not_found"));
        assertThat(missingMaterial.jdbc.updateCalls()).isEmpty();
        assertThat(missingMaterial.jdbc.callsContaining("FROM vouchers")).isEmpty();

        Fixture insufficient = new Fixture(BillingMode.CREDIT);
        insufficient.jdbc.balance = 2;
        assertThatThrownBy(() -> insufficient.service.create(
                        insufficient.userId,
                        insufficient.packageId,
                        insufficient.sessionId,
                        insufficient.materialId,
                        "insufficient"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(402);
                    assertThat(error.detail()).isEqualTo("insufficient_credits");
                });
        assertThat(insufficient.jdbc.updateCalls()).isEmpty();
    }

    @Test
    void activeAndGetAreOwnerScopedOrderStagesAndHideSnapshots() {
        Fixture fixture = new Fixture(BillingMode.CREDIT);
        fixture.jdbc.balance = 5;
        fixture.service.create(
                fixture.userId,
                fixture.packageId,
                fixture.sessionId,
                fixture.materialId,
                "projection-key");
        fixture.jdbc.clearCalls();

        JobInterviewPackageView active = fixture.service.active(fixture.userId).orElseThrow();
        JobInterviewPackageView fetched = fixture.service.get(fixture.userId, fixture.packageId);

        assertThat(active).isEqualTo(fetched);
        assertThat(active.stages())
                .extracting(JobInterviewPackageView.Stage::sequence)
                .containsExactly(1, 2, 3);
        assertThat(fixture.jdbc.singleCallContaining("status = 'ACTIVE'").sql())
                .contains("user_id = ?", "expires_at > now()", "ORDER BY created_at DESC");
        assertThat(fixture.jdbc.callsContaining("FROM interview_packages p"))
                .allSatisfy(call -> assertThat(call.sql()).contains("p.user_id = ?"));
        assertThat(fixture.jdbc.callsContaining("FROM interview_package_stages s"))
                .allSatisfy(call -> assertThat(call.sql())
                        .contains("JOIN interview_packages p", "p.user_id = ?", "ORDER BY s.sequence_no"));
        assertThat(JobInterviewPackageView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("materialSnapshot", "contextSnapshot", "planSnapshot", "voucherId", "userId");

        assertThatThrownBy(() -> fixture.service.get(UUID.randomUUID(), fixture.packageId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("job_interview_package_not_found"));
    }

    @Test
    void databaseConflictStopsImmediatelyWithoutPostFailureQuery() {
        Fixture packageConflict = new Fixture(BillingMode.CREDIT);
        packageConflict.jdbc.failOnSql = "INSERT INTO interview_packages";
        assertThatThrownBy(() -> packageConflict.service.create(
                        packageConflict.userId,
                        packageConflict.packageId,
                        packageConflict.sessionId,
                        packageConflict.materialId,
                        "package-conflict"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.detail()).isEqualTo("job_interview_package_conflict");
                });
        assertThat(packageConflict.jdbc.calls.getLast().sql()).contains("INSERT INTO interview_packages");

        Fixture sessionConflict = new Fixture(BillingMode.CREDIT);
        sessionConflict.jdbc.failOnSql = "INSERT INTO sessions";
        assertThatThrownBy(() -> sessionConflict.service.create(
                        sessionConflict.userId,
                        sessionConflict.packageId,
                        sessionConflict.sessionId,
                        sessionConflict.materialId,
                        "session-conflict"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.detail()).isEqualTo("active_interview_exists");
                });
        assertThat(sessionConflict.jdbc.calls.getLast().sql()).contains("INSERT INTO sessions");
        assertThat(sessionConflict.jdbc.callsContaining("INSERT INTO turns")).isEmpty();
        assertThat(sessionConflict.jdbc.callsContaining("FROM interview_packages p")).isEmpty();
    }

    @Test
    void createIsTransactionalViewCollectionsAreImmutableAndStartStageRejectsStably() throws Exception {
        Method create = JdbcJobInterviewPackageService.class.getMethod(
                "create", UUID.class, UUID.class, UUID.class, UUID.class, String.class);
        assertThat(create.getAnnotation(Transactional.class)).isNotNull();

        List<String> sections = new ArrayList<>(List.of("INTRODUCTION"));
        JobInterviewPackageView.Stage stage = new JobInterviewPackageView.Stage(
                JobInterviewStage.TECHNICAL_FIRST, 1, "UNLOCKED", null, 8, 12, 50, sections);
        List<JobInterviewPackageView.Stage> stages = new ArrayList<>(List.of(stage));
        JobInterviewPackageView view = new JobInterviewPackageView(
                UUID.randomUUID(),
                "ACTIVE",
                JobInterviewStage.TECHNICAL_FIRST,
                3,
                false,
                NOW.plus(30, ChronoUnit.DAYS),
                stages);
        sections.add("FOUNDATIONS");
        stages.clear();
        assertThat(view.stages()).containsExactly(stage);
        assertThat(stage.requiredSections()).containsExactly("INTRODUCTION");
        assertThatThrownBy(() -> view.stages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> stage.requiredSections().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        Fixture fixture = new Fixture(BillingMode.CREDIT);
        assertThatThrownBy(() -> fixture.service.startStage(
                        fixture.userId,
                        fixture.packageId,
                        JobInterviewStage.TECHNICAL_SECOND,
                        UUID.randomUUID(),
                        "stage-key"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.detail()).isEqualTo("job_interview_stage_start_unavailable");
                });
        assertThat(fixture.jdbc.calls).isEmpty();
    }

    private enum BillingMode {
        CREDIT,
        VOUCHER,
        ADMIN
    }

    private static final class Fixture {
        private final UUID userId = UUID.randomUUID();
        private final UUID packageId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final UUID materialId = UUID.randomUUID();
        private final RecordingJdbcTemplate jdbc;
        private final JdbcJobInterviewPackageService service;

        private Fixture(BillingMode mode) {
            jdbc = new RecordingJdbcTemplate(userId, packageId, sessionId, materialId, mode);
            service = new JdbcJobInterviewPackageService(
                    jdbc, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    private record SqlCall(String kind, String sql, List<Object> args) {
        private SqlCall(String kind, String sql, Object[] args) {
            this(kind, sql, Collections.unmodifiableList(
                    new ArrayList<>(Arrays.asList(args.clone()))));
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final UUID userId;
        private final UUID requestedPackageId;
        private final UUID requestedSessionId;
        private final UUID materialId;
        private final BillingMode billingMode;
        private final UUID voucherId = UUID.randomUUID();
        private final List<SqlCall> calls = new ArrayList<>();
        private boolean userExists = true;
        private boolean materialExists = true;
        private int balance = 5;
        private String profileSummary = "后端工程师";
        private String jobTitle = "Java 开发";
        private String jobRequirements = "熟悉 Spring";
        private String keywordsJson = "[\"Java\",\"Spring\"]";
        private String failOnSql;
        private boolean packageCreated;
        private UUID packageId;
        private String packageKey;
        private String requestHash;
        private int chargedCredit;
        private boolean adminUnlimited;
        private Instant packageExpiresAt;

        private RecordingJdbcTemplate(
                UUID userId,
                UUID requestedPackageId,
                UUID requestedSessionId,
                UUID materialId,
                BillingMode billingMode) {
            this.userId = userId;
            this.requestedPackageId = requestedPackageId;
            this.requestedSessionId = requestedSessionId;
            this.materialId = materialId;
            this.billingMode = billingMode;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            record("query", sql, args);
            try {
                if (sql.contains("FROM interview_packages")
                        && sql.contains("start_idempotency_key = ?")) {
                    if (packageCreated && packageKey.equals(args[1])) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getObject("id", UUID.class)).thenReturn(packageId);
                        when(rs.getString("request_hash")).thenReturn(requestHash);
                        return List.of(map(mapper, rs));
                    }
                    return List.of();
                }
                if (sql.contains("FROM users") && sql.contains("FOR UPDATE")) {
                    if (!userExists) {
                        return List.of();
                    }
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getInt("credit_balance")).thenReturn(balance);
                    when(rs.getString("role"))
                            .thenReturn(billingMode == BillingMode.ADMIN ? "admin" : "user");
                    return List.of(map(mapper, rs));
                }
                if (sql.contains("FROM materials")) {
                    if (!materialExists) {
                        return List.of();
                    }
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("profile_summary")).thenReturn(profileSummary);
                    when(rs.getString("job_title")).thenReturn(jobTitle);
                    when(rs.getString("job_requirements")).thenReturn(jobRequirements);
                    when(rs.getString("keywords_json")).thenReturn(keywordsJson);
                    return List.of(map(mapper, rs));
                }
                if (sql.contains("FROM vouchers")) {
                    if (billingMode != BillingMode.VOUCHER) {
                        return List.of();
                    }
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(voucherId);
                    return List.of(map(mapper, rs));
                }
                if (sql.contains("status = 'ACTIVE'") && sql.contains("FROM interview_packages")) {
                    if (!packageCreated || !userId.equals(args[0])) {
                        return List.of();
                    }
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(packageId);
                    return List.of(map(mapper, rs));
                }
                if (sql.contains("FROM interview_packages p")) {
                    if (!packageCreated || !packageId.equals(args[0]) || !userId.equals(args[1])) {
                        return List.of();
                    }
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(packageId);
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    when(rs.getString("current_stage_code")).thenReturn("TECHNICAL_FIRST");
                    when(rs.getInt("charged_credit")).thenReturn(chargedCredit);
                    when(rs.getBoolean("admin_unlimited_usage")).thenReturn(adminUnlimited);
                    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(packageExpiresAt));
                    return List.of(map(mapper, rs));
                }
                if (sql.contains("FROM interview_package_stages s")) {
                    if (!packageCreated || !packageId.equals(args[0]) || !userId.equals(args[1])) {
                        return List.of();
                    }
                    List<T> rows = new ArrayList<>();
                    JobInterviewPlan plan = JobInterviewPlan.chineseEnterpriseV1();
                    for (JobInterviewPlan.StagePlan stage : plan.stages()) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("stage_code")).thenReturn(stage.code().name());
                        when(rs.getInt("sequence_no")).thenReturn(stage.code().sequence());
                        when(rs.getString("status")).thenReturn(
                                stage.code() == JobInterviewStage.TECHNICAL_FIRST
                                        ? "IN_PROGRESS"
                                        : "LOCKED");
                        when(rs.getObject("session_id", UUID.class)).thenReturn(
                                stage.code() == JobInterviewStage.TECHNICAL_FIRST
                                        ? requestedSessionId
                                        : null);
                        when(rs.getInt("min_turns")).thenReturn(stage.minTurns());
                        when(rs.getInt("max_turns")).thenReturn(stage.maxTurns());
                        when(rs.getInt("target_duration_minutes")).thenReturn(stage.targetMinutes());
                        when(rs.getString("required_sections_json"))
                                .thenReturn(JSON.writeValueAsString(stage.requiredSections()));
                        rows.add(map(mapper, rs));
                    }
                    return rows;
                }
                throw new AssertionError("Unexpected query: " + sql);
            } catch (tools.jackson.core.JacksonException exception) {
                throw new AssertionError(exception);
            } catch (SQLException exception) {
                throw new DataAccessResourceFailureException("Unable to create JDBC test row", exception);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            record("update", sql, args);
            if (failOnSql != null && sql.contains(failOnSql)) {
                throw new DataIntegrityViolationException("simulated database constraint");
            }
            if (sql.contains("INSERT INTO interview_packages")) {
                packageCreated = true;
                packageId = (UUID) args[0];
                packageKey = (String) args[4];
                requestHash = (String) args[5];
                chargedCredit = (Integer) args[6];
                adminUnlimited = (Boolean) args[7];
                packageExpiresAt = ((Timestamp) args[9]).toInstant();
            }
            return 1;
        }

        private <T> T map(RowMapper<T> mapper, ResultSet resultSet) throws SQLException {
            return mapper.mapRow(resultSet, 0);
        }

        private void record(String kind, String sql, Object[] args) {
            calls.add(new SqlCall(kind, sql, args));
        }

        private List<SqlCall> callsContaining(String fragment) {
            return calls.stream().filter(call -> call.sql().contains(fragment)).toList();
        }

        private SqlCall singleCallContaining(String fragment) {
            return callsContaining(fragment).getFirst();
        }

        private List<SqlCall> updateCalls() {
            return calls.stream().filter(call -> call.kind().equals("update")).toList();
        }

        private int indexOf(String fragment) {
            return indexOfAfter(fragment, 0);
        }

        private int indexOfAfter(String fragment, int startIndex) {
            for (int index = startIndex; index < calls.size(); index++) {
                if (calls.get(index).sql().contains(fragment)) {
                    return index;
                }
            }
            return -1;
        }

        private int lastIndexOf(String fragment) {
            for (int index = calls.size() - 1; index >= 0; index--) {
                if (calls.get(index).sql().contains(fragment)) {
                    return index;
                }
            }
            return -1;
        }

        private void clearCalls() {
            calls.clear();
        }
    }
}
