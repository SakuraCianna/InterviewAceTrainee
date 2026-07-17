package icu.sakuracianna.mianba.interview.safety;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 识别回答中的提示注入特征，仅产生运营观察记录，不据此拒绝用户回答。
 *
 * 面试题可能正好讨论 AI 安全，因此启发式规则只用于审计和趋势分析。
 * 真正的模型边界由 Worker 系统提示、输出结构校验和最小权限共同保证。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public final class AnswerSafetyPolicy {
    private static final List<Rule> RULES = List.of(
            new Rule("ignore_prior_instructions", "prompt_injection", Pattern.compile(
                    "(?is)(忽略.{0,24}(以上|之前|系统|开发者).{0,24}(指令|提示)|"
                            + "(ignore|disregard).{0,40}(previous|above|system|developer).{0,24}(instruction|prompt))")),
            new Rule("request_internal_prompt", "prompt_exfiltration", Pattern.compile(
                    "(?is)((输出|泄露|显示).{0,24}(系统提示|内部提示|提示词|密钥)|"
                            + "(reveal|print|show).{0,32}(system prompt|developer message|secret|api key))")),
            new Rule("unrestricted_roleplay", "policy_bypass", Pattern.compile(
                    "(?is)((扮演|进入).{0,24}(无约束|不受限制|开发者模式)|"
                            + "(act as|enter).{0,32}(unrestricted|developer mode|jailbreak))")));

    /** 返回去重后的风险类别和规则 ID；未命中时不创建日志。 */
    public Optional<Finding> assess(String answer) {
        Set<String> categories = new LinkedHashSet<>();
        Set<String> ruleIds = new LinkedHashSet<>();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(answer).find()) {
                categories.add(rule.category());
                ruleIds.add(rule.id());
            }
        }
        if (ruleIds.isEmpty()) {
            return Optional.empty();
        }
        String riskLevel = ruleIds.size() >= 2 ? "medium" : "low";
        return Optional.of(new Finding(
                riskLevel, List.copyOf(categories), List.copyOf(ruleIds),
                "answer_prompt_injection_observed"));
    }

    /** 不含回答原文的安全观察结果。 */
    public record Finding(
            String riskLevel,
            List<String> categories,
            List<String> matchedRuleIds,
            String messageCode) {
    }

    private record Rule(String id, String category, Pattern pattern) {
    }
}
