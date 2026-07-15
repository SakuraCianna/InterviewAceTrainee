package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiJobWorkerReportTest {

    @Test
    void changingAnEarlierTurnChangesWholeInterviewScore() {
        List<AiJobWorker.TurnEvaluationRow> strongStart = List.of(
                turn(0, 90, "证据充分"), turn(1, 70, "结论可更聚焦"));
        List<AiJobWorker.TurnEvaluationRow> weakStart = List.of(
                turn(0, 30, "缺少具体行动"), turn(1, 70, "结论可更聚焦"));

        AiJobWorker.ReportAggregation first = AiJobWorker.aggregateEvaluations("job", strongStart);
        AiJobWorker.ReportAggregation second = AiJobWorker.aggregateEvaluations("job", weakStart);

        assertThat(first.totalScore()).isEqualTo(80);
        assertThat(second.totalScore()).isEqualTo(50);
        assertThat(second.improvements().getFirst()).contains("第1轮", "缺少具体行动");
    }

    @Test
    void ieltsFinalReportUsesEnglishForEveryGeneratedUserVisibleField() {
        UUID sessionId = UUID.randomUUID();
        InterviewAiGenerator.InterviewAiInput input = new InterviewAiGenerator.InterviewAiInput(
                "ielts",
                "Part 1 · Familiar Topics",
                "What part of your daily routine would you most like to change?",
                "I would exercise before work because it helps me focus.",
                1,
                2,
                Map.of());
        List<AiJobWorker.TurnEvaluationRow> turns = List.of(
                new AiJobWorker.TurnEvaluationRow(
                        0,
                        "被污染的中文轮次",
                        "Please introduce yourself.",
                        "I am a software engineer who enjoys learning languages.",
                        82,
                        "The answer is clear and relevant, but it needs a more specific example."),
                new AiJobWorker.TurnEvaluationRow(
                        1,
                        "另一个中文轮次",
                        "What part of your daily routine would you most like to change?",
                        "I would exercise before work because it helps me focus.",
                        76,
                        "The response is coherent; vary the sentence structures and add detail."));

        Map<String, Object> report = AiJobWorker.assembleReport(sessionId, input, turns).body();

        assertThat(report.get("readiness_level")).isEqualTo("Mostly ready");
        assertThat(report.get("score_explanation").toString())
                .contains("rounded arithmetic mean", "practice guidance only");
        assertThat(report.get("summary").toString()).contains("overall average score");
        assertThat(report.get("next_plan").toString()).contains("Rewrite", "timed");
        assertThat(report.get("recommended_drills").toString()).contains("IELTS", "speaking");
        assertThat(report.get("dimensions").toString())
                .contains("Part 1 · Introduction", "Part 1 · Familiar Topics");
        assertThat(report.get("turns").toString())
                .contains("Part 1 · Introduction", "Part 1 · Familiar Topics");
        assertThat(report.toString()).doesNotContainPattern("[\\p{IsHan}]");
    }

    @Test
    void nonIeltsFinalReportKeepsChineseGeneratedCopy() {
        InterviewAiGenerator.InterviewAiInput input = new InterviewAiGenerator.InterviewAiInput(
                "job", "岗位能力", "请说明一次具体决策。", "我先核对数据再制定方案。", 0, 1, Map.of());
        List<AiJobWorker.TurnEvaluationRow> turns = List.of(new AiJobWorker.TurnEvaluationRow(
                0, "岗位能力", "请说明一次具体决策。", "我先核对数据再制定方案。",
                68, "行动过程清楚，但缺少可验证的结果。"));

        Map<String, Object> report = AiJobWorker.assembleReport(UUID.randomUUID(), input, turns).body();

        assertThat(report.get("readiness_level")).isEqualTo("需要加强");
        assertThat(report.get("score_explanation").toString()).contains("算术平均值", "训练参考");
        assertThat(report.get("summary").toString()).contains("本场完成", "优先改进");
        assertThat(report.get("next_plan").toString()).contains("重写", "限时复述");
        assertThat(report.get("recommended_drills").toString()).contains("STAR", "限时表达");
    }

    private static AiJobWorker.TurnEvaluationRow turn(int index, int score, String feedback) {
        return new AiJobWorker.TurnEvaluationRow(
                index, "轮次" + (index + 1), "问题", "回答", score, feedback);
    }
}
