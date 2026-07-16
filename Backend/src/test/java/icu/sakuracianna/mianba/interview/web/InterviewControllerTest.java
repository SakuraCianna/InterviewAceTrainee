package icu.sakuracianna.mianba.interview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.aiwork.service.TaskView;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.interview.service.AnswerAcceptance;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.interview.service.InterviewView;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class InterviewControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void jobProductExposesPackageCostAndThreePlanBackedStages() {
        InterviewProductController.InterviewProduct product = new InterviewProductController().get("job");
        JsonNode stages = JSON.valueToTree(product).get("stages");

        assertThat(product.id()).isEqualTo("job");
        assertThat(product.name()).isEqualTo("求职面试");
        assertThat(product.credits()).isEqualTo(3);
        assertThat(product.turns()).isEqualTo(32);
        assertThat(product.description()).contains("两轮技术面", "HR 综合面");
        assertThat(stages).hasSize(3);
        assertStage(stages.get(0), "TECHNICAL_FIRST", "技术一面", 1, 8, 12, 50);
        assertStage(stages.get(1), "TECHNICAL_SECOND", "技术二面", 2, 7, 12, 60);
        assertStage(stages.get(2), "HR_FINAL", "HR 面", 3, 5, 8, 25);
    }

    @Test
    void productJsonUsesSnakeCasePackageAndStageFields() {
        JsonNode product = JSON.valueToTree(new InterviewProductController().get("job"));

        assertThat(product.get("package_required").asBoolean()).isTrue();
        assertThat(product.has("packageRequired")).isFalse();
        assertThat(product.get("stages").get(0).get("min_turns").asInt()).isEqualTo(8);
        assertThat(product.get("stages").get(0).get("max_turns").asInt()).isEqualTo(12);
        assertThat(product.get("stages").get(0).get("target_duration_minutes").asInt()).isEqualTo(50);
    }

    @Test
    void standaloneProductsKeepLegacyMetadataAndHaveNoStages() {
        InterviewProductController controller = new InterviewProductController();

        assertThat(controller.list()).filteredOn(product -> !product.id().equals("job"))
                .extracting(
                        InterviewProductController.InterviewProduct::id,
                        InterviewProductController.InterviewProduct::name,
                        InterviewProductController.InterviewProduct::credits,
                        InterviewProductController.InterviewProduct::turns,
                        InterviewProductController.InterviewProduct::description)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "postgraduate", "考研复试", 1, 5, "专业基础、研究动机与表达训练"),
                        org.assertj.core.groups.Tuple.tuple(
                                "civil_service", "公考结构化", 1, 5, "综合分析、组织协调与应急表达"),
                        org.assertj.core.groups.Tuple.tuple(
                                "ielts", "雅思口语", 2, 6, "Part 1-3 英语口语模拟"));
        assertThat(controller.list()).filteredOn(product -> !product.id().equals("job"))
                .allSatisfy(product -> {
                    JsonNode json = JSON.valueToTree(product);
                    assertThat(json.get("package_required").asBoolean()).isFalse();
                    assertThat(json.get("stages")).isEmpty();
                });
    }

    @Test
    void productStagesAreImmutableAndUnknownProductRemainsNotFound() {
        InterviewProductController controller = new InterviewProductController();
        List<InterviewProductController.InterviewProductStage> stages = controller.get("job").stages();

        assertThatThrownBy(() -> stages.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> controller.get("unknown"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.detail()).isEqualTo("interview_product_not_found");
                });
    }

    private static void assertStage(
            JsonNode stage,
            String code,
            String name,
            int sequence,
            int minTurns,
            int maxTurns,
            int targetDurationMinutes) {
        assertThat(stage.get("code").stringValue()).isEqualTo(code);
        assertThat(stage.get("name").stringValue()).isEqualTo(name);
        assertThat(stage.get("sequence").asInt()).isEqualTo(sequence);
        assertThat(stage.get("min_turns").asInt()).isEqualTo(minTurns);
        assertThat(stage.get("max_turns").asInt()).isEqualTo(maxTurns);
        assertThat(stage.get("target_duration_minutes").asInt()).isEqualTo(targetDurationMinutes);
    }

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
