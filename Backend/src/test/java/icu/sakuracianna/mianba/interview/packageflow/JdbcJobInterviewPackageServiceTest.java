package icu.sakuracianna.mianba.interview.packageflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.platform.web.ApiException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class JdbcJobInterviewPackageServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");
    private static final String OPENING = "请结合目标岗位，介绍一段最能体现关键能力的真实经历。";

    @Test
    void legacyPersistentMaterialCreateIsGoneBeforeJdbcAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcJobInterviewPackageService service = service(jdbc);

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "legacy"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(410);
                    assertThat(error.detail()).isEqualTo("persistent_material_flow_removed");
                });
        verifyNoInteractions(jdbc);
    }

    @Test
    void personalizedCreateKeepsBillingAndAggregateWithoutPrivateMaterialPersistence() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcJobInterviewPackageService service = service(jdbc);

        JobInterviewPackageView view = service.createPersonalized(
                jdbc.userId, jdbc.packageId, jdbc.sessionId, "package-key", OPENING);

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.chargedCredit()).isEqualTo(3);
        assertThat(view.stages())
                .extracting(JobInterviewPackageView.Stage::status)
                .containsExactly("IN_PROGRESS", "LOCKED", "LOCKED");
        assertThat(jdbc.calls).allSatisfy(call -> assertThat(call.sql().toLowerCase())
                .doesNotContain("materials", "material_id", "material_snapshot", "resume_text",
                        "job_requirements", "profile_summary"));
        assertThat(jdbc.single("INSERT INTO interview_packages").args()).hasSize(10);
        assertThat(jdbc.single("INSERT INTO sessions").args())
                .doesNotContain(jdbc.materialSentinel)
                .contains(jdbc.sessionId, jdbc.userId, 12, false);
        assertThat(jdbc.single("INSERT INTO turns").args())
                .containsExactly(jdbc.sessionId, "技术一面 · 自我介绍", OPENING, Timestamp.from(NOW));
        assertThat(jdbc.single("UPDATE users").args()).containsExactly(3, jdbc.userId);
        assertThat(jdbc.single("INSERT INTO credit_ledger").args())
                .contains(jdbc.userId, -3, 4, jdbc.packageId);
    }

    @Test
    void personalizedReplayReturnsOriginalWithoutSecondWrite() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcJobInterviewPackageService service = service(jdbc);
        JobInterviewPackageView original = service.createPersonalized(
                jdbc.userId, jdbc.packageId, jdbc.sessionId, "same-key", OPENING);
        int writes = jdbc.updateCount();

        JobInterviewPackageView replay = service.createPersonalized(
                jdbc.userId, jdbc.packageId, jdbc.sessionId, "same-key", OPENING);

        assertThat(replay).isEqualTo(original);
        assertThat(jdbc.updateCount()).isEqualTo(writes);
    }

    private static JdbcJobInterviewPackageService service(JdbcTemplate jdbc) {
        return new JdbcJobInterviewPackageService(
                jdbc, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private record SqlCall(String kind, String sql, List<Object> args) {
        private SqlCall(String kind, String sql, Object[] args) {
            this(kind, sql, Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args.clone()))));
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final UUID userId = UUID.randomUUID();
        private final UUID packageId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final UUID materialSentinel = UUID.randomUUID();
        private final List<SqlCall> calls = new ArrayList<>();
        private boolean created;
        private String idempotencyKey;
        private String requestHash;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            calls.add(new SqlCall("query", sql, args));
            try {
                if (sql.contains("start_idempotency_key = ?")) {
                    if (!created || !idempotencyKey.equals(args[1])) {
                        return List.of();
                    }
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(packageId);
                    when(rs.getString("request_hash")).thenReturn(requestHash);
                    return List.of(mapper.mapRow(rs, 0));
                }
                if (sql.contains("FROM users")) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getInt("credit_balance")).thenReturn(7);
                    when(rs.getString("role")).thenReturn("user");
                    return List.of(mapper.mapRow(rs, 0));
                }
                if (sql.contains("FROM vouchers")) {
                    return List.of();
                }
                if (sql.contains("FROM interview_packages p")) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(packageId);
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    when(rs.getString("current_stage_code")).thenReturn("TECHNICAL_FIRST");
                    when(rs.getInt("charged_credit")).thenReturn(3);
                    when(rs.getBoolean("admin_unlimited_usage")).thenReturn(false);
                    when(rs.getTimestamp("expires_at"))
                            .thenReturn(Timestamp.from(NOW.plus(30, ChronoUnit.DAYS)));
                    return List.of(mapper.mapRow(rs, 0));
                }
                if (sql.contains("FROM interview_package_stages s")) {
                    List<T> rows = new ArrayList<>();
                    for (JobInterviewPlan.StagePlan stage : JobInterviewPlan.chineseEnterpriseV1().stages()) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("stage_code")).thenReturn(stage.code().name());
                        when(rs.getInt("sequence_no")).thenReturn(stage.code().sequence());
                        when(rs.getString("status")).thenReturn(
                                stage.code() == JobInterviewStage.TECHNICAL_FIRST ? "IN_PROGRESS" : "LOCKED");
                        when(rs.getObject("session_id", UUID.class)).thenReturn(
                                stage.code() == JobInterviewStage.TECHNICAL_FIRST ? sessionId : null);
                        when(rs.getInt("min_turns")).thenReturn(stage.minTurns());
                        when(rs.getInt("max_turns")).thenReturn(stage.maxTurns());
                        when(rs.getInt("target_duration_minutes")).thenReturn(stage.targetMinutes());
                        when(rs.getString("required_sections_json"))
                                .thenReturn(new ObjectMapper().writeValueAsString(stage.requiredSections()));
                        rows.add(mapper.mapRow(rs, 0));
                    }
                    return rows;
                }
                throw new AssertionError("Unexpected query: " + sql);
            } catch (SQLException | tools.jackson.core.JacksonException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            calls.add(new SqlCall("update", sql, args));
            if (sql.contains("INSERT INTO interview_packages")) {
                created = true;
                idempotencyKey = (String) args[3];
                requestHash = (String) args[4];
            }
            return 1;
        }

        private SqlCall single(String fragment) {
            return calls.stream().filter(call -> call.sql().contains(fragment)).findFirst().orElseThrow();
        }

        private int updateCount() {
            return (int) calls.stream().filter(call -> call.kind().equals("update")).count();
        }
    }
}
