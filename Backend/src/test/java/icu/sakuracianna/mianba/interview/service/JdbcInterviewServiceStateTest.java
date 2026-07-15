package icu.sakuracianna.mianba.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.aiwork.service.TaskService;
import icu.sakuracianna.mianba.interview.safety.AnswerSafetyPolicy;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class JdbcInterviewServiceStateTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @Test
    void startRejectsMaterialWhoseRetentionDoesNotCoverTheWholeSession() {
        InterviewStateJdbcTemplate jdbc = new InterviewStateJdbcTemplate();
        JdbcInterviewService service = service(jdbc);

        assertThatThrownBy(() -> service.start(
                UUID.randomUUID(), UUID.randomUUID(), "job", UUID.randomUUID(), "start-key"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("interview_material_not_found"));
    }

    @Test
    void speechContextReturnsStableExpiredError() {
        InterviewStateJdbcTemplate jdbc = new InterviewStateJdbcTemplate();
        jdbc.returnExpiredSpeechState = true;
        JdbcInterviewService service = service(jdbc);

        assertThatThrownBy(() -> service.requireSpeechContext(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("interview_session_expired"));
    }

    @Test
    void answerReturnsStableExpiredErrorBeforeCreatingTask() {
        InterviewStateJdbcTemplate jdbc = new InterviewStateJdbcTemplate();
        jdbc.returnExpiredAnswerState = true;
        JdbcInterviewService service = service(jdbc);

        assertThatThrownBy(() -> service.answer(
                UUID.randomUUID(), UUID.randomUUID(), "answer-key", 0, "回答", "request-id"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("interview_session_expired"));
        assertThat(jdbc.updates)
                .noneMatch(sql -> sql.contains("INSERT INTO ai_jobs"));
    }

    private static JdbcInterviewService service(JdbcTemplate jdbc) {
        AnswerSafetyPolicy safetyPolicy = mock(AnswerSafetyPolicy.class);
        when(safetyPolicy.assess(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        return new JdbcInterviewService(
                jdbc,
                mock(ObjectMapper.class),
                mock(TaskService.class),
                safetyPolicy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(SessionDeletionCoordinator.class));
    }

    private static final class InterviewStateJdbcTemplate extends JdbcTemplate {
        private final java.util.ArrayList<String> updates = new java.util.ArrayList<>();
        private boolean returnExpiredSpeechState;
        private boolean returnExpiredAnswerState;

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("FROM materials")) {
                long matchingMaterials = sql.contains("retention_until >= ?")
                        ? 0L
                        : 1L;
                return requiredType.cast(matchingMaterials);
            }
            return null;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            try {
                if (returnExpiredSpeechState && sql.contains("s.status AS session_status")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("interview_type")).thenReturn("job");
                    when(resultSet.getString("session_status")).thenReturn("active");
                    when(resultSet.getInt("current_turn_index")).thenReturn(0);
                    when(resultSet.getString("turn_status")).thenReturn("waiting_answer");
                    when(resultSet.getString("question_text")).thenReturn("问题");
                    when(resultSet.getBoolean("unexpired")).thenReturn(false);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                if (returnExpiredAnswerState && sql.contains("FOR UPDATE OF s")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("status")).thenReturn("active");
                    when(resultSet.getInt("current_turn_index")).thenReturn(0);
                    when(resultSet.getInt("total_turns")).thenReturn(2);
                    when(resultSet.getBoolean("unexpired")).thenReturn(false);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                return List.of();
            } catch (Exception exception) {
                throw new DataAccessResourceFailureException("Unable to create JDBC test row", exception);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 0;
        }
    }
}
