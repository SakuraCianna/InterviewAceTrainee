package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicInterviewAiGeneratorTest {
    private final DeterministicInterviewAiGenerator generator = new DeterministicInterviewAiGenerator();

    @Test
    void nonFinalTurnReturnsStableStructuredEvaluationAndNextQuestion() {
        InterviewEvaluation evaluation = generator.evaluate(input(1, 5));

        assertThat(evaluation.score()).isEqualTo(81);
        assertThat(evaluation.feedback()).contains("确定性评估");
        assertThat(evaluation.dimensions()).hasSize(1);
        assertThat(evaluation.coveredSections()).containsExactly("INCIDENT_RESPONSE");
        assertThat(evaluation.roundName()).isEqualTo("INCIDENT_RESPONSE");
        assertThat(evaluation.nextQuestion()).contains("服务大厅突发系统故障");
        assertThat(evaluation.nextTopicCode()).isEqualTo("DETERMINISTIC_TOPIC");
    }

    @Test
    void finalTurnReturnsExplicitEndSuggestionAndEmptyNextQuestionMetadata() {
        InterviewEvaluation evaluation = generator.evaluate(input(4, 5));

        assertThat(evaluation.score()).isEqualTo(84);
        assertThat(evaluation.shouldEndStage()).isTrue();
        assertThat(evaluation.nextQuestion()).isEmpty();
        assertThat(evaluation.nextSection()).isEmpty();
        assertThat(evaluation.nextQuestionType()).isEmpty();
        assertThat(evaluation.nextTopicCode()).isEmpty();
    }

    @Test
    void ieltsStubUsesEnglishVisibleTextAndAllowedCodes() {
        InterviewAiGenerator.InterviewAiInput input = new InterviewAiGenerator.InterviewAiInput(
                "ielts", "Part 1 · Introduction", "What do you do?", "I am a student.",
                0, 6, Map.of());

        InterviewEvaluation evaluation = generator.evaluate(input);

        assertThat(evaluation.feedback()).doesNotContainPattern("[\\p{IsHan}]");
        assertThat(evaluation.dimensions().getFirst().evidence())
                .doesNotContainPattern("[\\p{IsHan}]");
        assertThat(evaluation.coveredSections()).containsExactly("PART_1");
        assertThat(evaluation.roundName()).isEqualTo("PART_1");
        assertThat(evaluation.nextQuestion()).isEqualTo(
                "What part of your daily routine would you most like to change, and why?");
    }

    private static InterviewAiGenerator.InterviewAiInput input(int turnIndex, int totalTurns) {
        return new InterviewAiGenerator.InterviewAiInput(
                "civil_service",
                "结构化模拟",
                "请分析公共服务中的效率与公平。",
                "我会先定义目标，再列出约束和执行步骤。",
                turnIndex,
                totalTurns,
                Map.of());
    }
}
