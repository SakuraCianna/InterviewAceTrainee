package icu.sakuracianna.mianba.interview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.aiwork.service.TaskView;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.interview.service.AnswerAcceptance;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.interview.service.InterviewView;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class InterviewControllerTest {

    @Test
    void answerRequiresIdempotencyAndReturnsAsyncTaskContract() {
        InterviewService service = mock(InterviewService.class);
        InterviewController controller = new InterviewController(service);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, "user@example.com", "user", UUID.randomUUID(), 0);
        InterviewView session = new InterviewView(
                sessionId, "civil_service", "awaiting_ai", 0, 5,
                new InterviewView.Question(0, "结构化模拟", "请分析该现象。"), null, null,
                Instant.parse("2026-07-14T12:00:00Z"));
        TaskView task = new TaskView(
                taskId, sessionId, "GENERATE_FOLLOW_UP", "QUEUED", "WAITING_FOR_WORKER",
                0, 0, 3, true, 0, null, null,
                Instant.parse("2026-07-14T12:00:01Z"), Instant.parse("2026-07-14T12:00:01Z"));
        when(service.answer(userId, sessionId, "idem-answer-1", 0, "回答内容", "req_answer"))
                .thenReturn(new AnswerAcceptance(session, task));

        ResponseEntity<InterviewController.AsyncAnswerResponse> response = controller.answer(
                sessionId,
                "idem-answer-1",
                new InterviewController.AnswerRequest(0, "回答内容"),
                principal,
                "req_answer");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/tasks/" + taskId);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().requestId()).isEqualTo("req_answer");
        assertThat(response.getBody().task().id()).isEqualTo(taskId);
    }
}
