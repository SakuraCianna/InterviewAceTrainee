package icu.sakuracianna.mianba.aiwork.worker;

import java.util.List;

/** Worker 严格解析并校验后的多维面试评价结果。 */
public record InterviewEvaluation(
        int score,
        String feedback,
        List<DimensionEvaluation> dimensions,
        List<String> coveredSections,
        List<String> coveredTopics,
        List<String> riskFlags,
        boolean shouldEndStage,
        String nextQuestion,
        String nextSection,
        String nextQuestionType,
        String nextTopicCode) {

    public InterviewEvaluation {
        dimensions = List.copyOf(dimensions);
        coveredSections = List.copyOf(coveredSections);
        coveredTopics = List.copyOf(coveredTopics);
        riskFlags = List.copyOf(riskFlags);
    }

    /** Task 5 前保留给既有 Worker 测试替身的四字段构造方式。 */
    public InterviewEvaluation(int score, String feedback, String roundName, String nextQuestion) {
        this(
                score,
                feedback,
                List.of(new DimensionEvaluation(
                        "LEGACY", score, "兼容旧 Worker 的已校验评价", "等待结构化持久化迁移")),
                List.of(),
                List.of(),
                List.of(),
                nextQuestion == null,
                nextQuestion,
                roundName,
                roundName,
                "LEGACY");
    }

    /**
     * 旧 Worker 在 Task 5 迁移前仍读取 roundName；结构化契约以可信 nextSection 作为兼容值。
     */
    public String roundName() {
        return nextSection;
    }
}
