package icu.sakuracianna.mianba.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.List;
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
                .contains("section_code = NULL")
                .contains("question_type = NULL")
                .contains("topic_code = NULL")
                .contains("parent_turn_id = NULL")
                .contains("DELETE FROM reports")
                .contains("content_excerpt = NULL");
    }

    @Test
    void conversationErasureClearsEveryStageContextInTheSessionPackageOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = new SessionContentEraser(jdbc);
        UUID sessionId = UUID.randomUUID();

        eraser.eraseConversation(sessionId);

        List<String> updates = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                .map(invocation -> String.valueOf(invocation.getArguments()[0]))
                .toList();
        String stageUpdate = updates.stream()
                .filter(sql -> sql.contains("UPDATE interview_package_stages"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing package stage context erasure"));
        assertThat(stageUpdate)
                .contains("context_snapshot = '{}'::jsonb")
                .contains("content_erased_at = COALESCE(content_erased_at, now())")
                .contains("version = version + 1")
                .contains("source.session_id = ?")
                .contains("target.package_id")
                .contains("context_snapshot <> '{}'::jsonb OR target.content_erased_at IS NULL")
                .doesNotContain("user_id")
                .doesNotContain("material_snapshot")
                .doesNotContain("plan_snapshot");
        assertThat(updates)
                .noneMatch(sql -> sql.contains("UPDATE interview_packages"))
                .noneMatch(sql -> sql.contains("material_snapshot"))
                .noneMatch(sql -> sql.contains("plan_snapshot"));
    }
}
