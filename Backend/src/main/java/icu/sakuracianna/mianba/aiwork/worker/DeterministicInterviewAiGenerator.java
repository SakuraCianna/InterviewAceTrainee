package icu.sakuracianna.mianba.aiwork.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 为完整 CI 拓扑提供不访问外部模型的确定性生成器。
 *
 * 该实现必须同时满足 Worker、非生产和显式开关三个条件；生产启动门禁还会再次拒绝
 * stub 开关，防止测试实现因错误配置进入真实业务链路。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "worker")
@ConditionalOnProperty(
        name = "mianba.runtime.production",
        havingValue = "false",
        matchIfMissing = true)
@ConditionalOnProperty(name = "mianba.ai-runtime.stub-enabled", havingValue = "true")
final class DeterministicInterviewAiGenerator implements InterviewAiGenerator {

    @Override
    public InterviewEvaluation evaluate(InterviewAiInput input) {
        InterviewPromptPolicy.Profile profile = InterviewPromptPolicy.profile(input.interviewType());
        String nextQuestion = input.finalTurn()
                ? null
                : InterviewPromptPolicy.fallbackQuestion(input);
        String feedback = profile.englishOnly()
                ? profile.feedbackFallback()
                : "回答已完成确定性评估：结构清晰，建议补充可验证的事实和结果。";
        return new InterviewEvaluation(
                80 + Math.min(input.turnIndex(), 10),
                feedback,
                InterviewPromptPolicy.nextStage(input),
                nextQuestion).normalized(input);
    }
}
