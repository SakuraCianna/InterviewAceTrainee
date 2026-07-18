package icu.sakuracianna.mianba.interview.safety;

import icu.sakuracianna.mianba.aiwork.worker.AiWorkerException;
import icu.sakuracianna.mianba.aiwork.worker.DimensionEvaluation;
import icu.sakuracianna.mianba.aiwork.worker.InterviewEvaluation;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** 在模型输出写库、展示或送往 TTS 前执行统一的规范化安全检查。 */
public final class AiOutputSafetyPolicy {
    private static final String NEUTRAL_FEEDBACK =
            "该段内容超出训练反馈边界，请仅依据可观察证据继续练习。";
    private static final List<Rule> RULES = List.of(
            new Rule("output_secret_exfiltration", "secret_exfiltration", true, Pattern.compile(
                    "(?is)(系统提示|开发者消息|api.?密钥|访问令牌|system.?prompt|developer.?message|api.?key|access.?token)")),
            new Rule("output_dangerous_instructions", "dangerous_instructions", true, Pattern.compile(
                    "(?is)(自制炸药|制作爆炸物|绕过门锁|入侵账号|制造毒品|build.{0,20}explosive|steal.{0,20}credential)")),
            new Rule("output_self_harm_instructions", "self_harm", true, Pattern.compile(
                    "(?is)(自杀方法|如何自残|结束生命的方法|how.{0,20}(suicide|self.?harm))")),
            new Rule("output_minor_sexual_content", "sexual_minors", true, Pattern.compile(
                    "(?is)(未成年.{0,12}(色情|性行为)|儿童色情|child.{0,12}(sexual|porn))")),
            new Rule("output_discriminatory_decision", "discrimination", true, Pattern.compile(
                    "(?is)((因为|基于).{0,16}(性别|民族|种族|宗教|残疾).{0,24}(拒绝|淘汰|不录用)|"
                            + "reject.{0,24}(gender|race|religion|disability))")),
            new Rule("output_decisive_outcome", "decision_boundary", false, Pattern.compile(
                    "(?is)(决定录用|保证录用|保证通过面试|确定录取|保证录取|一定能考上|"
                            + "guaranteed.{0,16}(hire|admission|pass)|you are hired|you will be admitted)")));

    private AiOutputSafetyPolicy() {
    }

    /** 返回安全评价；高风险拒绝，越过训练决策边界的低风险文本替换为中性反馈。 */
    public static InterviewEvaluation sanitize(InterviewEvaluation evaluation) {
        Optional<Finding> finding = assess(evaluation);
        if (finding.filter(Finding::blocked).isPresent()) {
            throw new AiWorkerException("AI_OUTPUT_BLOCKED", "模型输出未通过安全检查", false);
        }
        return new InterviewEvaluation(
                evaluation.score(),
                replaceIfNeeded(evaluation.feedback()),
                evaluation.dimensions().stream()
                        .map(dimension -> new DimensionEvaluation(
                                dimension.code(),
                                dimension.score(),
                                replaceIfNeeded(dimension.evidence()),
                                replaceIfNeeded(dimension.comment())))
                        .toList(),
                evaluation.coveredSections(),
                evaluation.coveredTopics(),
                evaluation.riskFlags(),
                evaluation.shouldEndStage(),
                evaluation.nextQuestion() == null ? null : replaceIfNeeded(evaluation.nextQuestion()),
                evaluation.nextSection(),
                evaluation.nextQuestionType(),
                evaluation.nextTopicCode());
    }

    /** 对 TTS 文本实施最后一道高风险阻断，避免不安全模型文本被外发。 */
    public static void requireSafeForTts(String text) {
        assess(text).filter(Finding::blocked).ifPresent(finding -> {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "unsafe_tts_output", "当前问题未通过安全检查，无法合成语音");
        });
    }

    /** 报告总结命中任一规则时回退到服务端模板。 */
    public static String safeSummaryOrNull(String summary) {
        return assess(summary).isPresent() ? null : summary;
    }

    public static Optional<Finding> assess(InterviewEvaluation evaluation) {
        List<String> values = new ArrayList<>();
        values.add(evaluation.feedback());
        evaluation.dimensions().forEach(dimension -> {
            values.add(dimension.evidence());
            values.add(dimension.comment());
        });
        values.add(evaluation.nextQuestion());
        return assess(values.toArray(String[]::new));
    }

    /** 生成仅在当前调用栈内使用的规范审计输入；调用方不得持久化或记录该字符串。 */
    public static String auditText(InterviewEvaluation evaluation) {
        StringBuilder value = new StringBuilder(evaluation.feedback());
        evaluation.dimensions().forEach(dimension -> value
                .append('\n').append(dimension.evidence())
                .append('\n').append(dimension.comment()));
        if (evaluation.nextQuestion() != null) {
            value.append('\n').append(evaluation.nextQuestion());
        }
        return value.toString();
    }

    public static Optional<Finding> assess(String... values) {
        Set<String> categories = new LinkedHashSet<>();
        Set<String> ruleIds = new LinkedHashSet<>();
        boolean blocked = false;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = AnswerSafetyPolicy.normalize(value);
            String compact = normalized.replaceAll("[^\\p{L}\\p{N}]", "");
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(normalized).find()
                        || rule.pattern().matcher(compact).find()) {
                    categories.add(rule.category());
                    ruleIds.add(rule.id());
                    blocked = blocked || rule.blocking();
                }
            }
        }
        if (ruleIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Finding(
                blocked ? "high" : "low",
                List.copyOf(categories),
                List.copyOf(ruleIds),
                blocked,
                blocked ? "ai_output_blocked" : "ai_output_replaced"));
    }

    private static String replaceIfNeeded(String value) {
        return assess(value).filter(finding -> !finding.blocked()).isPresent()
                ? NEUTRAL_FEEDBACK
                : value;
    }

    public record Finding(
            String riskLevel,
            List<String> categories,
            List<String> ruleIds,
            boolean blocked,
            String messageCode) {
    }

    private record Rule(String id, String category, boolean blocking, Pattern pattern) {
    }
}
