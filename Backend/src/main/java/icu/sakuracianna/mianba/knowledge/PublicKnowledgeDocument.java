package icu.sakuracianna.mianba.knowledge;

import java.util.List;
import java.util.Map;

/** 从仓库 Markdown 加载的公共文档；不允许承载任何用户上传内容。 */
public record PublicKnowledgeDocument(
        String id,
        KnowledgeDomain domain,
        String category,
        String title,
        List<String> aliases,
        List<String> tags,
        String schoolTier,
        String sourceVersion,
        List<String> sources,
        String content,
        String contentHash) {

    public PublicKnowledgeDocument {
        aliases = List.copyOf(aliases);
        tags = List.copyOf(tags);
        sources = List.copyOf(sources);
    }

    public Map<String, Object> metadata() {
        return Map.of(
                "domain", domain.code(),
                "category", category,
                "title", title,
                "aliases", String.join(",", aliases),
                "tags", String.join(",", tags),
                "school_tier", schoolTier,
                "source_version", sourceVersion,
                "content_hash", contentHash);
    }
}
