package icu.sakuracianna.mianba.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 应用启动后对公共 Markdown 执行一次幂等、增量 Redis 向量同步。 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class KnowledgeIndexSynchronizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexSynchronizer.class);
    private static final int BATCH_SIZE = 16;
    private static final int MINIMUM_JOB_DOCUMENTS = 200;
    private static final int MINIMUM_POSTGRADUATE_DOCUMENTS = 30;
    private static final int MAXIMUM_CHUNK_CODE_POINTS = 220;
    private static final String MANIFEST_KEY = "mianba:knowledge:manifest";

    private final KnowledgeProperties properties;
    private final KnowledgeDocumentLoader loader;
    private final ObjectProvider<VectorStore> vectorStores;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public KnowledgeIndexSynchronizer(
            KnowledgeProperties properties,
            KnowledgeDocumentLoader loader,
            ObjectProvider<VectorStore> vectorStores,
            StringRedisTemplate redis,
            JdbcTemplate jdbc) {
        this.properties = properties;
        this.loader = loader;
        this.vectorStores = vectorStores;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeAfterStartup() {
        if (!properties.enabled() || !properties.synchronizeOnStartup()) {
            updateDisabledState();
            return;
        }
        Thread.ofVirtual().name("mianba-knowledge-indexer").start(this::synchronize);
    }

    void synchronize() {
        Instant startedAt = Instant.now();
        try {
            VectorStore vectorStore = vectorStores.getIfAvailable();
            if (vectorStore == null) {
                throw new IllegalStateException("VectorStore bean is unavailable");
            }
            List<PublicKnowledgeDocument> documents = loader.load(properties.corpusLocation());
            validateCoverage(documents);
            int jobDocumentCount = (int) documents.stream()
                    .filter(document -> document.domain() == KnowledgeDomain.JOB).count();
            int postgraduateDocumentCount = documents.size() - jobDocumentCount;
            String corpusHash = corpusHash(documents);
            List<Document> vectorDocuments = documents.stream()
                    .flatMap(document -> toVectorDocuments(document).stream())
                    .toList();
            updateIndexingState(documents.size(), jobDocumentCount, postgraduateDocumentCount,
                    vectorDocuments.size(), corpusHash, startedAt);

            Map<Object, Object> existingManifest = redis.opsForHash().entries(MANIFEST_KEY);
            Map<String, String> expectedManifest = new LinkedHashMap<>();
            List<Document> changed = new ArrayList<>();
            for (Document document : vectorDocuments) {
                String sourceHash = document.getMetadata().get("content_hash").toString();
                String versionedHash = KnowledgeDocumentLoader.sha256(
                        properties.modelId() + ':' + properties.vectorDimensions() + ':'
                                + sourceHash + ':' + KnowledgeDocumentLoader.sha256(document.getText()));
                expectedManifest.put(document.getId(), versionedHash);
                if (!versionedHash.equals(existingManifest.get(document.getId()))) {
                    changed.add(document);
                }
            }
            Set<String> staleIds = new TreeSet<>();
            existingManifest.keySet().stream().map(Object::toString)
                    .filter(id -> !expectedManifest.containsKey(id))
                    .forEach(staleIds::add);
            if (!staleIds.isEmpty()) {
                vectorStore.delete(List.copyOf(staleIds));
                redis.opsForHash().delete(MANIFEST_KEY, staleIds.toArray());
            }
            for (int offset = 0; offset < changed.size(); offset += BATCH_SIZE) {
                List<Document> batch = changed.subList(offset, Math.min(offset + BATCH_SIZE, changed.size()));
                vectorStore.add(batch);
                for (Document document : batch) {
                    redis.opsForHash().put(MANIFEST_KEY, document.getId(), expectedManifest.get(document.getId()));
                }
            }
            completeState(documents.size(), jobDocumentCount, postgraduateDocumentCount,
                    vectorDocuments.size(), corpusHash, startedAt);
            LOGGER.info("Public knowledge index ready; documents={}, chunks={}, changed={}, removed={}, corpusVersion={}",
                    documents.size(), vectorDocuments.size(), changed.size(), staleIds.size(),
                    properties.corpusVersion());
        } catch (RuntimeException exception) {
            failState(exception, startedAt);
            LOGGER.error("Public knowledge index failed; errorType={}", exception.getClass().getSimpleName());
        }
    }

    private static List<Document> toVectorDocuments(PublicKnowledgeDocument source) {
        String[] sections = source.content().split("(?m)(?=^## )");
        List<Document> chunks = new ArrayList<>(sections.length);
        int chunkIndex = 0;
        for (String section : sections) {
            String normalized = section.strip();
            if (normalized.isEmpty() || !normalized.startsWith("## ")) {
                continue;
            }
            String sectionName = normalized.lines().findFirst().orElse("## 公共知识").substring(3).strip();
            String bounded = boundCodePoints(normalized, MAXIMUM_CHUNK_CODE_POINTS);
            Map<String, Object> metadata = new LinkedHashMap<>(source.metadata());
            metadata.put("source_id", source.id());
            metadata.put("section", sectionName);
            chunks.add(Document.builder()
                    .id(source.id() + '#' + chunkIndex++)
                    .text(source.title() + "\n" + bounded)
                    .metadata(metadata)
                    .build());
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Public knowledge document has no indexable sections: " + source.id());
        }
        return List.copyOf(chunks);
    }

    private static String boundCodePoints(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static void validateCoverage(List<PublicKnowledgeDocument> documents) {
        long jobCount = documents.stream().filter(document -> document.domain() == KnowledgeDomain.JOB).count();
        long postgraduateCount = documents.stream()
                .filter(document -> document.domain() == KnowledgeDomain.POSTGRADUATE).count();
        if (jobCount < MINIMUM_JOB_DOCUMENTS || postgraduateCount < MINIMUM_POSTGRADUATE_DOCUMENTS) {
            throw new IllegalStateException("Public knowledge corpus coverage is below the required minimum");
        }
    }

    private static String corpusHash(List<PublicKnowledgeDocument> documents) {
        String canonical = documents.stream()
                .sorted(Comparator.comparing(PublicKnowledgeDocument::id))
                .map(document -> document.id() + ':' + document.contentHash())
                .reduce("", (left, right) -> left + right + '\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void updateDisabledState() {
        try {
            jdbc.update("""
                    UPDATE knowledge_index_state
                    SET status = 'DISABLED', corpus_version = ?, model_id = ?,
                        vector_dimensions = ?, last_error_code = NULL, updated_at = now()
                    WHERE singleton_id = 1
                    """, properties.corpusVersion(), properties.modelId(), properties.vectorDimensions());
        } catch (RuntimeException ignored) {
            // 测试环境可显式关闭 Flyway；知识库停用时不要求状态表存在。
        }
    }

    private void updateIndexingState(
            int documentCount,
            int jobDocumentCount,
            int postgraduateDocumentCount,
            int chunkCount,
            String corpusHash,
            Instant startedAt) {
        jdbc.update("""
                UPDATE knowledge_index_state
                SET status = 'INDEXING', corpus_version = ?, corpus_hash = ?, model_id = ?,
                    vector_dimensions = ?, document_count = ?, job_document_count = ?,
                    postgraduate_document_count = ?, chunk_count = ?,
                    indexed_chunk_count = 0, failure_count = 0, last_error_code = NULL,
                    started_at = ?, completed_at = NULL, updated_at = now()
                WHERE singleton_id = 1
                """, properties.corpusVersion(), corpusHash, properties.modelId(),
                properties.vectorDimensions(), documentCount, jobDocumentCount,
                postgraduateDocumentCount, chunkCount, Timestamp.from(startedAt));
    }

    private void completeState(
            int documentCount,
            int jobDocumentCount,
            int postgraduateDocumentCount,
            int chunkCount,
            String corpusHash,
            Instant startedAt) {
        jdbc.update("""
                UPDATE knowledge_index_state
                SET status = 'READY', corpus_version = ?, corpus_hash = ?, model_id = ?,
                    vector_dimensions = ?, document_count = ?, job_document_count = ?,
                    postgraduate_document_count = ?, chunk_count = ?,
                    indexed_chunk_count = ?, failure_count = 0, last_error_code = NULL,
                    started_at = ?, completed_at = now(), updated_at = now()
                WHERE singleton_id = 1
                """, properties.corpusVersion(), corpusHash, properties.modelId(),
                properties.vectorDimensions(), documentCount, jobDocumentCount,
                postgraduateDocumentCount, chunkCount, chunkCount, Timestamp.from(startedAt));
    }

    private void failState(RuntimeException exception, Instant startedAt) {
        String code = "KNOWLEDGE_INDEX_" + exception.getClass().getSimpleName().toUpperCase();
        try {
            jdbc.update("""
                    UPDATE knowledge_index_state
                    SET status = 'FAILED', failure_count = failure_count + 1,
                        last_error_code = ?, started_at = COALESCE(started_at, ?),
                        completed_at = now(), updated_at = now()
                    WHERE singleton_id = 1
                    """, code.substring(0, Math.min(80, code.length())), Timestamp.from(startedAt));
        } catch (RuntimeException ignored) {
            // 原始故障优先，且绝不把文档内容拼进二次错误日志。
        }
    }
}
