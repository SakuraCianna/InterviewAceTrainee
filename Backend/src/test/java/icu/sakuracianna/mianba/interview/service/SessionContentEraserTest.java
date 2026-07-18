package icu.sakuracianna.mianba.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.List;
import java.util.UUID;
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

        List<String> updates = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                .map(invocation -> String.valueOf(invocation.getArguments()[0]))
                .toList();
        String sql = String.join("\n", updates);
        assertThat(sql)
                .contains("input_ref = '{}'::jsonb")
                .contains("result_ref = NULL")
                .contains("request_hash = encode(digest('mianba:erased-ai-job:v1', 'sha256'), 'hex')")
                .contains("DELETE FROM turn_dimension_scores")
                .contains("answer_text = NULL")
                .contains("evaluation_feedback = NULL")
                .contains("section_code = NULL")
                .contains("question_type = NULL")
                .contains("topic_code = NULL")
                .contains("parent_turn_id = NULL")
                .contains("covered_sections = '[]'::jsonb")
                .contains("covered_topics = '[]'::jsonb")
                .contains("risk_flags = '[]'::jsonb")
                .contains("DELETE FROM reports WHERE session_id = ?")
                .contains("report_scope = 'PACKAGE'")
                .contains("rule_ids = '[]'::jsonb")
                .contains("content_digest = NULL")
                .contains("request_id = NULL")
                .doesNotContain("content_excerpt", "matched_terms");
        int dimensionDelete = indexContaining(updates, "DELETE FROM turn_dimension_scores");
        int turnUpdate = indexContaining(updates, "UPDATE turns");
        assertThat(dimensionDelete).isLessThan(turnUpdate);
    }

    @Test
    void jobErasureIncludesOnlySessionJobsAndPackageScopedReportJobs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = new SessionContentEraser(jdbc);
        UUID sessionId = UUID.randomUUID();

        eraser.eraseJobs(sessionId, "SESSION_DELETED");

        String jobUpdate = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                .map(invocation -> String.valueOf(invocation.getArguments()[0]))
                .filter(sql -> sql.contains("UPDATE ai_jobs"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing AI job erasure"));
        assertThat(jobUpdate)
                .contains("session_id = ?")
                .contains("session_id IS NULL")
                .contains("kind = 'GENERATE_REPORT'")
                .contains("package_id IN")
                .contains("FROM interview_package_stages")
                .contains("source.session_id = ?")
                .doesNotContain("kind = 'GENERATE_FOLLOW_UP'");
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

    private static int indexContaining(List<String> sqlStatements, String fragment) {
        for (int index = 0; index < sqlStatements.size(); index++) {
            if (sqlStatements.get(index).contains(fragment)) {
                return index;
            }
        }
        throw new AssertionError("Missing SQL containing " + fragment);
    }
}
