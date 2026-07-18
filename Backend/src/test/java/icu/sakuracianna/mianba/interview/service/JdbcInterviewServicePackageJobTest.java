package icu.sakuracianna.mianba.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.aiwork.service.TaskService;
import icu.sakuracianna.mianba.aiwork.service.TaskView;
import icu.sakuracianna.mianba.interview.safety.AnswerSafetyPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JdbcInterviewServicePackageJobTest {
    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void packageAnswerQueuesPackageOwnedJobWithoutCopyingMaterial() throws Exception {
        Fixture fixture = new Fixture(List.of(UUID.randomUUID()));

        fixture.answer("package-answer");

        SqlCall jobInsert = fixture.jdbc.singleUpdateContaining("INSERT INTO ai_jobs");
        JsonNode input = JSON.readTree(jobInsert.jsonArgument());
        assertThat(jobInsert.sql()).contains("package_id");
        assertThat(jobInsert.args().get(3)).isEqualTo(fixture.packageIds.getFirst());
        assertThat(input.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("session_id", "turn_index");
        assertThat(input.get("session_id").stringValue()).isEqualTo(fixture.sessionId.toString());
        assertThat(input.get("turn_index").intValue()).isZero();
        assertThat(jobInsert.jsonArgument())
                .doesNotContain(
                        "material_context",
                        "profile_summary",
                        "job_requirements",
                        "immutable resume snapshot",
                        "sensitive job description");
    }

    @Test
    void nonPackageAnswerNeverQueuesPrivateMaterialContext() throws Exception {
        Fixture fixture = new Fixture(List.of());

        fixture.answer("legacy-answer");

        SqlCall jobInsert = fixture.jdbc.singleUpdateContaining("INSERT INTO ai_jobs");
        JsonNode input = JSON.readTree(jobInsert.jsonArgument());
        assertThat(jobInsert.sql()).contains("package_id");
        assertThat(jobInsert.args().get(3)).isNull();
        assertThat(input.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("session_id", "turn_index");
        assertThat(jobInsert.jsonArgument())
                .doesNotContain("material_context", "immutable resume snapshot", "sensitive job description");
    }

    @Test
    void inconsistentMultiplePackageBindingsFailBeforeAnyWrite() {
        Fixture fixture = new Fixture(List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> fixture.answer("corrupt-binding"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple interview packages");
        assertThat(fixture.jdbc.updateCalls).isEmpty();
    }

    @Test
    void idempotentReplayReturnsExistingTaskWithoutResolvingPackageOrWriting() {
        Fixture fixture = new Fixture(List.of(UUID.randomUUID()));
        fixture.jdbc.replay = true;

        fixture.answer("replayed-answer");

        assertThat(fixture.jdbc.updateCalls).isEmpty();
        assertThat(fixture.jdbc.queries)
                .noneMatch(sql -> sql.contains("FROM interview_package_stages"));
    }

    private static final class Fixture {
        private final UUID userId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final List<UUID> packageIds;
        private final PackageJobJdbcTemplate jdbc;
        private final JdbcInterviewService service;

        private Fixture(List<UUID> packageIds) {
            this.packageIds = List.copyOf(packageIds);
            jdbc = new PackageJobJdbcTemplate(sessionId, this.packageIds);
            TaskService tasks = mock(TaskService.class);
            AnswerSafetyPolicy safety = mock(AnswerSafetyPolicy.class);
            TaskView task = task(sessionId, jdbc.jobId);
            when(safety.assess(anyString())).thenReturn(Optional.empty());
            when(tasks.findByOwnerAndIdempotency(
                    org.mockito.ArgumentMatchers.eq(userId), anyString()))
                    .thenReturn(Optional.of(task));
            when(tasks.findCurrentForOwnerSession(userId, sessionId)).thenReturn(Optional.empty());
            when(tasks.get(userId, false, jdbc.jobId)).thenReturn(task);
            service = new JdbcInterviewService(
                    jdbc,
                    JSON,
                    tasks,
                    safety,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(SessionDeletionCoordinator.class));
        }

        private AnswerAcceptance answer(String idempotencyKey) {
            return service.answer(
                    userId,
                    sessionId,
                    idempotencyKey,
                    0,
                    "回答包含明确证据",
                    "req-package-answer");
        }
    }

    private static final class PackageJobJdbcTemplate extends JdbcTemplate {
        private final UUID sessionId;
        private final List<UUID> packageIds;
        private final UUID jobId = UUID.randomUUID();
        private final List<String> queries = new ArrayList<>();
        private final List<SqlCall> updateCalls = new ArrayList<>();
        private boolean replay;

        private PackageJobJdbcTemplate(UUID sessionId, List<UUID> packageIds) {
            this.sessionId = sessionId;
            this.packageIds = packageIds;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            try {
                if (sql.contains("FROM ai_jobs j")) {
                    if (!replay) {
                        return List.of();
                    }
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getObject("id", UUID.class)).thenReturn(jobId);
                    when(resultSet.getObject("session_id", UUID.class)).thenReturn(sessionId);
                    when(resultSet.getString("request_hash"))
                            .thenReturn(sha256("0\n回答包含明确证据"));
                    when(resultSet.getInt("turn_index")).thenReturn(0);
                    return List.of(map(mapper, resultSet));
                }
                if (sql.contains("FOR UPDATE OF s")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("status")).thenReturn("active");
                    when(resultSet.getInt("current_turn_index")).thenReturn(0);
                    when(resultSet.getInt("total_turns")).thenReturn(12);
                    when(resultSet.getBoolean("unexpired")).thenReturn(true);
                    when(resultSet.getString("profile_summary"))
                            .thenReturn("immutable resume snapshot");
                    when(resultSet.getString("job_title")).thenReturn("Backend Engineer");
                    when(resultSet.getString("job_requirements"))
                            .thenReturn("sensitive job description");
                    return List.of(map(mapper, resultSet));
                }
                if (sql.contains("FROM interview_package_stages")) {
                    List<T> bindings = new ArrayList<>();
                    for (UUID packageId : packageIds) {
                        ResultSet resultSet = mock(ResultSet.class);
                        when(resultSet.getObject("package_id", UUID.class)).thenReturn(packageId);
                        bindings.add(map(mapper, resultSet));
                    }
                    return bindings;
                }
                if (sql.contains("SELECT s.id, s.interview_type")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getObject("id", UUID.class)).thenReturn(sessionId);
                    when(resultSet.getString("interview_type")).thenReturn("job");
                    when(resultSet.getString("status")).thenReturn("awaiting_ai");
                    when(resultSet.getInt("current_turn_index")).thenReturn(0);
                    when(resultSet.getInt("total_turns")).thenReturn(12);
                    when(resultSet.getInt("turn_index")).thenReturn(0);
                    when(resultSet.wasNull()).thenReturn(false);
                    when(resultSet.getString("round_name")).thenReturn("一面");
                    when(resultSet.getString("question_text")).thenReturn("请介绍自己");
                    when(resultSet.getString("report_json")).thenReturn(null);
                    when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
                    return List.of(map(mapper, resultSet));
                }
                throw new AssertionError("Unexpected query: " + sql);
            } catch (Exception exception) {
                throw new DataAccessResourceFailureException("Unable to map JDBC test row", exception);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            updateCalls.add(new SqlCall(sql, args));
            return 1;
        }

        private SqlCall singleUpdateContaining(String fragment) {
            return updateCalls.stream()
                    .filter(call -> call.sql().contains(fragment))
                    .reduce((first, second) -> {
                        throw new AssertionError("Multiple updates contain " + fragment);
                    })
                    .orElseThrow(() -> new AssertionError("Missing update containing " + fragment));
        }

        private <T> T map(RowMapper<T> mapper, ResultSet resultSet) throws Exception {
            return mapper.mapRow(resultSet, 0);
        }
    }

    private record SqlCall(String sql, List<Object> args) {
        private SqlCall(String sql, Object[] args) {
            this(sql, Collections.unmodifiableList(
                    new ArrayList<>(Arrays.asList(args.clone()))));
        }

        private String jsonArgument() {
            return args.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(value -> value.startsWith("{"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing JSON argument"));
        }
    }

    private static TaskView task(UUID sessionId, UUID taskId) {
        return new TaskView(
                taskId,
                sessionId,
                "GENERATE_FOLLOW_UP",
                "QUEUED",
                "WAITING_FOR_WORKER",
                0,
                0,
                3,
                true,
                0,
                null,
                null,
                NOW,
                NOW);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
