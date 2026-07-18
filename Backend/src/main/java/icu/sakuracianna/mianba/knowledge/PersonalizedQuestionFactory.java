package icu.sakuracianna.mianba.knowledge;

import java.util.List;
import org.springframework.stereotype.Component;

/** 只根据公共检索结果生成可持久化首题，不复制用户材料或公共文档正文。 */
@Component
public final class PersonalizedQuestionFactory {
    private static final String JOB_FALLBACK =
            "请结合一段真实经历，说明你如何理解目标岗位的核心任务、作出关键判断并验证结果。";
    private static final String POSTGRADUATE_FALLBACK =
            "请说明你的专业基础、报考动机，以及你希望进一步研究的一个具体问题。";

    public String openingQuestion(KnowledgeDomain domain, List<KnowledgeSnippet> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return domain == KnowledgeDomain.JOB ? JOB_FALLBACK : POSTGRADUATE_FALLBACK;
        }
        String topic = boundedTitle(snippets.getFirst().title());
        if (topic.isEmpty()) {
            return domain == KnowledgeDomain.JOB ? JOB_FALLBACK : POSTGRADUATE_FALLBACK;
        }
        String anchor = publicAnchor(snippets.getFirst().content(), topic);
        if (anchor.isEmpty()) {
            return domain == KnowledgeDomain.JOB ? JOB_FALLBACK : POSTGRADUATE_FALLBACK;
        }
        return domain == KnowledgeDomain.JOB
                ? "围绕“" + topic + "”，公开知识要点是：“" + anchor
                        + "”。请结合一段真实经历说明你的判断、行动、结果和复盘。"
                : "围绕“" + topic + "”，公开知识要点是：“" + anchor
                        + "”。请说明你的知识基础、真实经历、当前不足和读研阶段的学习计划。";
    }

    private static String boundedTitle(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static String publicAnchor(String content, String title) {
        if (content == null) {
            return "";
        }
        for (String line : content.lines().toList()) {
            String normalized = line.replace("**", "").strip()
                    .replaceFirst("^(?:[-*]|\\d+[.)])\\s*", "");
            if (normalized.isEmpty() || normalized.startsWith("#") || normalized.equals(title)) {
                continue;
            }
            int codePoints = normalized.codePointCount(0, normalized.length());
            if (codePoints < 8) {
                continue;
            }
            return codePoints <= 140
                    ? normalized
                    : normalized.substring(0, normalized.offsetByCodePoints(0, 140));
        }
        return "";
    }
}
