package icu.sakuracianna.mianba.aiwork.worker;

import java.util.List;
import java.util.Map;

/** 将 Provider 调用隔离在 Worker 内部的面试生成端口。 */
public interface InterviewAiGenerator {
    /**
     * 根据当前轮次上下文生成结构化评估与下一问题。
     *
     * @param input 已完成边界裁剪的面试上下文
     * @return 结构化模型结果
     */
    InterviewEvaluation evaluate(InterviewAiInput input);

    /**
     * 传递给模型适配器的最小面试上下文。
     * 材料字段已经过筛选，不包含原始简历全文；适配器仍必须将其视为不可信输入。
     */
    record InterviewAiInput(
            String interviewType,
            String roundName,
            String question,
            String answer,
            int turnIndex,
            int totalTurns,
            Map<String, String> materialContext,
            List<String> previousQuestions) {
        public InterviewAiInput {
            materialContext = Map.copyOf(materialContext == null ? Map.of() : materialContext);
            previousQuestions = List.copyOf(previousQuestions == null ? List.of() : previousQuestions);
        }

        /** 保留无历史问题调用方式，供测试替身和独立适配器兼容使用。 */
        public InterviewAiInput(
                String interviewType,
                String roundName,
                String question,
                String answer,
                int turnIndex,
                int totalTurns,
                Map<String, String> materialContext) {
            this(interviewType, roundName, question, answer, turnIndex, totalTurns,
                    materialContext, List.of());
        }

        /** 判断当前回答完成后是否应生成最终报告。 */
        public boolean finalTurn() {
            return turnIndex + 1 >= totalTurns;
        }

        /** 返回补充既往问题后的不可变输入，避免 Worker 暴露可变集合。 */
        public InterviewAiInput withPreviousQuestions(List<String> questions) {
            return new InterviewAiInput(
                    interviewType, roundName, question, answer, turnIndex, totalTurns,
                    materialContext, questions);
        }
    }
}
