package icu.sakuracianna.mianba.knowledge;

/** 可安全进入面试提示词的公共知识片段。 */
public record KnowledgeSnippet(
        String documentId,
        String title,
        String category,
        String content,
        double score) {
}
