package icu.sakuracianna.mianba.aiwork.worker;

import java.util.Comparator;
import java.util.List;
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
        InterviewPromptPolicy.StagePolicy stage = InterviewPromptPolicy.stagePolicy(input);
        List<String> sections = stage.sections().stream().sorted(Comparator.naturalOrder()).toList();
        String section = sections.get(Math.floorMod(input.turnIndex(), sections.size()));
        String nextSection = sections.get(Math.floorMod(input.turnIndex() + 1, sections.size()));
        List<String> questionTypes = stage.questionTypes().stream().sorted().toList();
        String nextQuestionType = questionTypes.get(
                Math.floorMod(input.turnIndex() + 1, questionTypes.size()));
        String nextQuestion = input.finalTurn()
                ? ""
                : InterviewPromptPolicy.fallbackQuestion(input);
        String feedback = profile.englishOnly()
                ? "The answer is clear and relevant; add one specific, verifiable example."
                : "回答已完成确定性评估：结构清晰，建议补充可验证的事实和结果。";
        String evidence = profile.englishOnly()
                ? "The response presents one relevant main idea."
                : "回答给出了与当前问题相关的明确观点";
        String comment = profile.englishOnly()
                ? "Add a concrete example and explain its significance."
                : "建议补充具体事实、边界和结果";
        int score = 80 + Math.min(input.turnIndex(), 10);
        InterviewEvaluation evaluation = new InterviewEvaluation(
                score,
                feedback,
                List.of(new DimensionEvaluation(
                        stage.dimensions().stream().sorted().findFirst().orElseThrow(),
                        score,
                        evidence,
                        comment)),
                List.of(section),
                List.of("DETERMINISTIC_TOPIC"),
                List.of(),
                input.finalTurn(),
                nextQuestion,
                input.finalTurn() ? "" : nextSection,
                input.finalTurn() ? "" : nextQuestionType,
                input.finalTurn() ? "" : "DETERMINISTIC_TOPIC");
        return SpringAiInterviewGenerator.validateEvaluation(evaluation, input);
    }
}
