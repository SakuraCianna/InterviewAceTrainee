package icu.sakuracianna.mianba.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionContentEraserTest {

    @Test
    void erasureCoversEveryStoredInterviewContentCopy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = new SessionContentEraser(jdbc);
        UUID sessionId = UUID.randomUUID();

        eraser.eraseJobs(sessionId, "SESSION_DELETED");
        eraser.eraseConversation(sessionId);

        String sql = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                .map(invocation -> String.valueOf(invocation.getArguments()[0]))
                .collect(Collectors.joining("\n"));
        assertThat(sql)
                .contains("input_ref = '{}'::jsonb")
                .contains("result_ref = NULL")
                .contains("request_hash = encode(digest('mianba:erased-ai-job:v1', 'sha256'), 'hex')")
                .contains("answer_text = NULL")
                .contains("evaluation_feedback = NULL")
                .contains("DELETE FROM reports")
                .contains("content_excerpt = NULL");
    }
}
