package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterviewEvaluationTest {

    @Test
    void normalizedReplacesDatabaseUnsafeControlsButPreservesUsefulWhitespace() {
        InterviewEvaluation normalized = new InterviewEvaluation(
                120,
                "反馈\u0000内容\n下一行\t保留",
                "\u0000追问",
                "说明\u0085细节")
                .normalized(false);

        assertThat(normalized.score()).isEqualTo(100);
        assertThat(normalized.feedback()).isEqualTo("反馈 内容\n下一行\t保留");
        assertThat(normalized.roundName()).isEqualTo("追问");
        assertThat(normalized.nextQuestion()).isEqualTo("说明 细节");
    }

    @Test
    void normalizedTruncatesAtUnicodeCodePointBoundary() {
        String question = "a".repeat(1999) + "😀" + "tail";

        String normalized = new InterviewEvaluation(80, "反馈", "追问", question)
                .normalized(false)
                .nextQuestion();

        assertThat(normalized).endsWith("😀");
        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(2000);
    }

    @Test
    void ieltsNormalizationReplacesNonEnglishVisibleOutput() {
        InterviewAiGenerator.InterviewAiInput input = new InterviewAiGenerator.InterviewAiInput(
                "ielts",
                "Part 1 · Introduction",
                "What do you usually do after work?",
                "I usually exercise or read.",
                0,
                6,
                Map.of(),
                List.of());

        InterviewEvaluation normalized = new InterviewEvaluation(
                76, "回答不错，但需要具体例子。", "第二部分", "请介绍一次旅行。")
                .normalized(input);

        assertThat(normalized.feedback())
                .contains("specific example")
                .doesNotContainPattern("[\\p{IsHan}]");
        assertThat(normalized.roundName()).isEqualTo("Part 1 · Familiar Topics");
        assertThat(normalized.nextQuestion())
                .isEqualTo("What part of your daily routine would you most like to change, and why?")
                .doesNotContainPattern("[\\p{IsHan}]");
    }

    @Test
    void normalizationReplacesRepeatedQuestionWithNextStageFallback() {
        String repeated = "请说明你如何处理一个具体难题以及取舍依据。";
        InterviewAiGenerator.InterviewAiInput input = new InterviewAiGenerator.InterviewAiInput(
                "job",
                "经历深挖",
                "请补充你的职责和结果。",
                "我负责设计并将错误率降低了百分之二十。",
                1,
                6,
                Map.of(),
                List.of(repeated));

        InterviewEvaluation normalized = new InterviewEvaluation(
                85, "回答提供了量化结果。", "任意模型阶段", repeated)
                .normalized(input);

        assertThat(normalized.roundName()).isEqualTo("岗位能力");
        assertThat(normalized.nextQuestion())
                .isEqualTo("针对目标岗位的一项核心能力，请说明你如何解决过一个具体难题以及取舍依据。")
                .isNotEqualTo(repeated);
    }
}
