package icu.sakuracianna.mianba.aiwork.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class JdbcTaskServiceTest {

    @Test
    void userQueriesExcludeTasksBelongingToDeletingOrDeletedSessions() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcTaskService service = service(jdbc);
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(userId, false, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("task_not_found"));
        service.findByOwnerAndIdempotency(userId, "answer:stable-key");
        service.findCurrentForOwnerSession(userId, UUID.randomUUID());
        assertThatThrownBy(() -> service.retry(
                userId, false, UUID.randomUUID(), "retry:stable-key", "request-id"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("task_not_found"));

        assertThat(jdbc.queries).hasSize(4).allSatisfy(sql -> assertThat(sql)
                .contains("session_row.status NOT IN ('deleting', 'deleted')"));
    }

    @Test
    void adminTaskProjectionKeepsMetadataButRedactsStoredContent() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcTaskService service = service(jdbc);

        assertThatThrownBy(() -> service.get(UUID.randomUUID(), true, UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        service.list(null, null, null, 25, 0);

        assertThat(jdbc.queries).hasSize(2).allSatisfy(sql -> assertThat(sql)
                .contains("NULL::text AS result_ref_json")
                .contains("error_code")
                .contains("NULL::varchar AS error_message")
                .doesNotContain("session_row.status NOT IN"));
    }

    private static JdbcTaskService service(JdbcTemplate jdbc) {
        return new JdbcTaskService(jdbc, new ObjectMapper(), Clock.systemUTC());
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> queries = new ArrayList<>();

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queries.add(sql);
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (requiredType == Long.class) {
                return requiredType.cast(0L);
            }
            throw new AssertionError("Unexpected queryForObject type: " + requiredType);
        }
    }
}
