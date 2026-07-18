package icu.sakuracianna.mianba.knowledge;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 使用 Redis 8 Search 执行公共知识向量检索，故障时返回空上下文并标记降级。 */
@Service
public class RedisKnowledgeRagService implements KnowledgeRagService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisKnowledgeRagService.class);
    private static final int MAXIMUM_QUERY_CODE_POINTS = 2500;
    private static final int MAXIMUM_SNIPPET_CODE_POINTS = 1800;

    private final ObjectProvider<VectorStore> vectorStores;
    private final KnowledgeProperties properties;
    private final JdbcTemplate jdbc;

    public RedisKnowledgeRagService(
            ObjectProvider<VectorStore> vectorStores,
            KnowledgeProperties properties,
            JdbcTemplate jdbc) {
        this.vectorStores = vectorStores;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public List<KnowledgeSnippet> retrieve(String query, KnowledgeDomain domain) {
        if (!properties.enabled() || query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = normalize(query, MAXIMUM_QUERY_CODE_POINTS);
        if (normalized.isEmpty()) {
            return List.of();
        }
        VectorStore vectorStore = vectorStores.getIfAvailable();
        if (vectorStore == null) {
            markDegraded("KNOWLEDGE_VECTOR_STORE_UNAVAILABLE");
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(normalized)
                    .topK(properties.topK())
                    .similarityThreshold(properties.similarityThreshold())
                    .filterExpression("domain == '" + domain.code() + "'")
                    .build();
            return vectorStore.similaritySearch(request).stream()
                    .map(RedisKnowledgeRagService::toSnippet)
                    .toList();
        } catch (RuntimeException exception) {
            String errorCode = "KNOWLEDGE_QUERY_" + exception.getClass().getSimpleName().toUpperCase();
            markDegraded(errorCode);
            LOGGER.warn("Public knowledge query degraded; errorType={}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private static KnowledgeSnippet toSnippet(Document document) {
        return new KnowledgeSnippet(
                document.getId(),
                metadata(document, "title"),
                metadata(document, "category"),
                normalize(document.getText(), MAXIMUM_SNIPPET_CODE_POINTS),
                document.getScore() == null ? 0 : document.getScore());
    }

    private static String metadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : normalize(value.toString(), 160);
    }

    private void markDegraded(String errorCode) {
        try {
            jdbc.update("""
                    UPDATE knowledge_index_state
                    SET status = 'DEGRADED', last_error_code = ?, updated_at = now()
                    WHERE singleton_id = 1
                    """, errorCode.substring(0, Math.min(80, errorCode.length())));
        } catch (RuntimeException ignored) {
            // 健康状态写入失败不能把公共 RAG 故障扩散到核心面试流程。
        }
    }

    static String normalize(String raw, int maximumCodePoints) {
        String value = raw.replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").trim();
        int count = value.codePointCount(0, value.length());
        return count <= maximumCodePoints
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }
}
