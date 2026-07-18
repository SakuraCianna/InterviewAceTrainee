package icu.sakuracianna.mianba.interview.safety;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 归一化并识别用户输入中的提示注入与越权特征。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public final class AnswerSafetyPolicy {
    private static final List<Rule> RULES = List.of(
            new Rule("ignore_prior_instructions", "prompt_injection", true, Pattern.compile(
                    "(?is)(忽略.{0,24}(以上|之前|系统|开发者).{0,24}(指令|提示)|"
                            + "(ignore|disregard).{0,40}(previous|above|system|developer).{0,24}(instruction|prompt))")),
            new Rule("request_internal_prompt", "prompt_exfiltration", true, Pattern.compile(
                    "(?is)((输出|泄露|显示).{0,24}(系统提示|内部提示|提示词|密钥)|"
                            + "(reveal|print|show).{0,32}(system prompt|developer message|secret|api key))")),
            new Rule("unrestricted_roleplay", "policy_bypass", true, Pattern.compile(
                    "(?is)((扮演|进入).{0,24}(无约束|不受限制|开发者模式)|"
                            + "(act as|enter).{0,32}(unrestricted|developer mode|jailbreak))")),
            new Rule("obfuscated_prompt_override", "prompt_injection", true, Pattern.compile(
                    "(?is)(忽略(之前|以上|系统|开发者)(指令|提示)|"
                            + "ignore(previous|above|system|developer)(instruction|prompt))")));

    /** 返回去重后的风险类别和规则 ID；未命中时不创建日志。 */
    public Optional<Finding> assess(String answer) {
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(answer);
        String compact = normalized.replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> categories = new LinkedHashSet<>();
        Set<String> ruleIds = new LinkedHashSet<>();
        boolean blocked = false;
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(normalized).find() || rule.pattern().matcher(compact).find()) {
                categories.add(rule.category());
                ruleIds.add(rule.id());
                blocked = blocked || rule.blocking();
            }
        }
        if (ruleIds.isEmpty()) {
            return Optional.empty();
        }
        String riskLevel = blocked ? "high" : ruleIds.size() >= 2 ? "medium" : "low";
        return Optional.of(new Finding(
                riskLevel, List.copyOf(categories), List.copyOf(ruleIds),
                blocked, blocked ? "input_prompt_injection_blocked" : "input_risk_observed"));
    }

    /** 高风险输入在解析、嵌入、持久化和模型调用前统一拒绝。 */
    public void requireAllowed(String source, String value) {
        assess(value).filter(Finding::blocked).ifPresent(finding -> {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "unsafe_" + safeSource(source), "输入包含不安全指令，已停止处理");
        });
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}\\p{Cc}]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeSource(String source) {
        if (source == null || !source.matches("[a-z0-9_]{1,40}")) {
            return "input";
        }
        return source;
    }

    /** 不含回答原文的安全观察结果。 */
    public record Finding(
            String riskLevel,
            List<String> categories,
            List<String> matchedRuleIds,
            boolean blocked,
            String messageCode) {
    }

    private record Rule(String id, String category, boolean blocking, Pattern pattern) {
    }
}
