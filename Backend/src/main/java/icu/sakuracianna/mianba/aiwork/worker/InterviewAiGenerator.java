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
     * 使用 AI 合成最终报告的叙事总结段落；实现可返回 null 以回退到规则模板。
     * 失败时静默降级，不中断面试完成流程。
     *
     * @param turns 已完成的所有轮次评价
     * @param interviewType 面试类型字符串
     * @return AI 生成的总结段落，或 null
     */
    default String synthesizeReportSummary(List<ReportTurn> turns, String interviewType) {
        return null;
    }

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
            List<String> previousQuestions,
            String jobStageCode) {
        public InterviewAiInput {
            materialContext = Map.copyOf(materialContext == null ? Map.of() : materialContext);
            previousQuestions = List.copyOf(previousQuestions == null ? List.of() : previousQuestions);
        }

        /** 保留 Task 5 前 Worker 的既有调用签名。 */
        public InterviewAiInput(
                String interviewType,
                String roundName,
                String question,
                String answer,
                int turnIndex,
                int totalTurns,
                Map<String, String> materialContext,
                List<String> previousQuestions) {
            this(interviewType, roundName, question, answer, turnIndex, totalTurns,
                    materialContext, previousQuestions, null);
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
                    materialContext, List.of(), null);
        }

        /** 判断当前回答完成后是否应生成最终报告。 */
        public boolean finalTurn() {
            return turnIndex + 1 >= totalTurns;
        }

        /** 返回补充既往问题后的不可变输入，避免 Worker 暴露可变集合。 */
        public InterviewAiInput withPreviousQuestions(List<String> questions) {
            return new InterviewAiInput(
                    interviewType, roundName, question, answer, turnIndex, totalTurns,
                    materialContext, questions, jobStageCode);
        }
    }

    /** 传递给报告合成器的单轮面试摘要，只包含已评价的安全字段。 */
    record ReportTurn(
            int turnIndex,
            String roundName,
            String question,
            String answer,
            int score,
            String feedback) {
    }
}
