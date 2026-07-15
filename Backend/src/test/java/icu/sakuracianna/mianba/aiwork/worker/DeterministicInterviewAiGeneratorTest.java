package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicInterviewAiGeneratorTest {
    private final DeterministicInterviewAiGenerator generator = new DeterministicInterviewAiGenerator();

    @Test
    void nonFinalTurnReturnsStableBoundedEvaluationAndNextQuestion() {
        InterviewEvaluation evaluation = generator.evaluate(input(1, 5));

        assertThat(evaluation.score()).isEqualTo(81);
        assertThat(evaluation.feedback()).contains("确定性评估");
        assertThat(evaluation.roundName()).isEqualTo("应急处置");
        assertThat(evaluation.nextQuestion()).contains("服务大厅突发系统故障");
    }

    @Test
    void finalTurnNeverReturnsAnotherQuestion() {
        InterviewEvaluation evaluation = generator.evaluate(input(4, 5));

        assertThat(evaluation.score()).isEqualTo(84);
        assertThat(evaluation.nextQuestion()).isNull();
    }

    @Test
    void ieltsStubUsesTheSameEnglishOnlyPolicyAsProductionGenerator() {
        InterviewAiGenerator.InterviewAiInput input = new InterviewAiGenerator.InterviewAiInput(
                "ielts", "Part 1 · Introduction", "What do you do?", "I am a student.",
                0, 6, Map.of());

        InterviewEvaluation evaluation = generator.evaluate(input);

        assertThat(evaluation.feedback()).doesNotContainPattern("[\\p{IsHan}]");
        assertThat(evaluation.roundName()).isEqualTo("Part 1 · Familiar Topics");
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
