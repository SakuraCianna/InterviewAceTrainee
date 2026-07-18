package icu.sakuracianna.mianba.platform.web;

import icu.sakuracianna.mianba.interview.material.MaterialParserClient;
import icu.sakuracianna.mianba.knowledge.KnowledgeDomain;
import icu.sakuracianna.mianba.knowledge.KnowledgeRagService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对外只返回最少健康信息；详细面试核心指标仅管理员可见。 */
@RestController
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class HealthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    private final MaterialParserClient materialParser;
    private final ObjectProvider<VectorStore> vectorStores;
    private final KnowledgeRagService knowledge;

    public HealthController(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            RabbitTemplate rabbit,
            Clock clock,
            MaterialParserClient materialParser,
            ObjectProvider<VectorStore> vectorStores,
            KnowledgeRagService knowledge) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.rabbit = rabbit;
        this.clock = clock;
        this.materialParser = materialParser;
        this.vectorStores = vectorStores;
        this.knowledge = knowledge;
    }

    /**
     * 返回进程存活状态，不访问外部依赖。
     * 该端点用于判断是否需要重启进程，不能替代流量就绪判断。
     */
    @GetMapping("/api/health")
    public Map<String, Object> liveness() {
        return Map.of("status", "ok", "timestamp", clock.instant());
    }

    /**
     * 验证数据库、Redis、RabbitMQ、新鲜 Worker 心跳和材料解析进程后返回流量就绪状态。
     * 任一关键依赖不可用时返回 503，避免请求进入无法完成的业务链路。
     */
    @GetMapping("/api/health/readiness")
    public Map<String, Object> readiness() {
        boolean infrastructureReady;
        boolean workerReady;
        try {
            Integer databaseProbe = jdbc.queryForObject("SELECT 1", Integer.class);
            redis.hasKey("mianba:readiness:nonexistent");
            Boolean rabbitProbe = rabbit.execute(channel -> channel.isOpen());
            Boolean workerProbe = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM runtime_heartbeats
                        WHERE role = 'worker' AND consumers_ready = true AND rabbit_ready = true
                          AND updated_at > now() - interval '30 seconds')
                    """, Boolean.class);
            infrastructureReady = databaseProbe != null
                    && databaseProbe == 1
                    && Boolean.TRUE.equals(rabbitProbe);
            workerReady = Boolean.TRUE.equals(workerProbe);
        } catch (RuntimeException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "infrastructure_not_ready", "基础设施尚未就绪");
        }
        if (!infrastructureReady || !workerReady) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "worker_not_ready", "AI Worker 尚未就绪");
        }
        boolean parserReady;
        try {
            parserReady = materialParser.isReady();
        } catch (RuntimeException exception) {
            parserReady = false;
        }
        if (!parserReady) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "material_parser_not_ready", "材料解析服务尚未就绪");
        }
        return Map.of(
                "ready", true,
                "database_ready", true,
                "redis_ready", true,
                "rabbit_ready", true,
                "worker_ready", true,
                "parser_ready", true,
                "timestamp", clock.instant());
    }

    /**
     * 返回管理员可见的面试核心能力状态；公共知识索引降级不会影响普通面试就绪状态。
     */
    @GetMapping("/api/health/interview-core")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> interviewCore() {
        Map<String, Object> indexState = jdbc.queryForMap("""
                SELECT status, corpus_version, model_id, vector_dimensions,
                       document_count, job_document_count, postgraduate_document_count,
                       chunk_count, indexed_chunk_count, failure_count,
                       last_error_code, started_at, completed_at, updated_at
                FROM knowledge_index_state
                WHERE singleton_id = 1
                """);
        long enabledProviders = count("SELECT count(*) FROM providers WHERE enabled = true");
        String indexStatus = String.valueOf(indexState.get("status"));
        long documentCount = number(indexState, "document_count");
        long jobDocumentCount = number(indexState, "job_document_count");
        long postgraduateDocumentCount = number(indexState, "postgraduate_document_count");
        long chunkCount = number(indexState, "chunk_count");
        long indexedChunkCount = number(indexState, "indexed_chunk_count");
        double coverage = chunkCount == 0 ? 0.0 : (double) indexedChunkCount / chunkCount;
        boolean ragEnabled = !"DISABLED".equals(indexStatus);
        boolean corpusReady = jobDocumentCount >= 200
                && postgraduateDocumentCount >= 30
                && documentCount == jobDocumentCount + postgraduateDocumentCount;
        boolean embeddingAvailable = vectorStores.getIfAvailable() != null;
        boolean vectorReady = "READY".equals(indexStatus)
                && indexedChunkCount == chunkCount && chunkCount > 0 && embeddingAvailable;
        boolean jobProbeReady = false;
        boolean postgraduateProbeReady = false;
        if (vectorReady) {
            try {
                jobProbeReady = !knowledge.retrieve(
                        "后端开发工程师 核心能力 实践问题", KnowledgeDomain.JOB).isEmpty();
                postgraduateProbeReady = !knowledge.retrieve(
                        "计算机科学与技术 复试 研究问题", KnowledgeDomain.POSTGRADUATE).isEmpty();
            } catch (RuntimeException ignored) {
                // 探针仅影响公共 RAG 状态，绝不把异常内容暴露给管理端。
            }
        }
        boolean ragReady = corpusReady && vectorReady && jobProbeReady && postgraduateProbeReady;

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("ready", corpusReady);
        cards.put("source_version", indexState.get("corpus_version"));
        cards.put("source_policy", "versioned-public-markdown");
        cards.put("total_document_count", documentCount);
        cards.put("job_document_count", jobDocumentCount);
        cards.put("postgraduate_document_count", postgraduateDocumentCount);
        cards.put("expected_minimums", Map.of("job", 200, "postgraduate", 30));
        cards.put("validation_enforced", true);

        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("ready", vectorReady);
        vectors.put("enabled", ragEnabled);
        vectors.put("status", indexStatus);
        vectors.put("store", "redis-search");
        vectors.put("document_count", documentCount);
        vectors.put("chunk_count", chunkCount);
        vectors.put("indexed_chunk_count", indexedChunkCount);
        vectors.put("coverage_rate", coverage);
        vectors.put("embedding_model", indexState.get("model_id"));
        vectors.put("embedding_provider_available", embeddingAvailable);
        vectors.put("vector_dimensions", indexState.get("vector_dimensions"));
        vectors.put("failure_count", indexState.get("failure_count"));
        vectors.put("last_error_code", indexState.get("last_error_code"));
        vectors.put("started_at", indexState.get("started_at"));
        vectors.put("completed_at", indexState.get("completed_at"));
        vectors.put("updated_at", indexState.get("updated_at"));

        Map<String, Object> recall = Map.of(
                "ready", jobProbeReady && postgraduateProbeReady,
                "enabled", ragEnabled,
                "status", indexStatus,
                "coverage_rate", coverage,
                "job_probe_ready", jobProbeReady,
                "postgraduate_probe_ready", postgraduateProbeReady);
        boolean providerReady = enabledProviders > 0;
        boolean ready = providerReady && ragReady;
        List<String> failureReasons = new java.util.ArrayList<>();
        if (!providerReady) {
            failureReasons.add("no_enabled_ai_provider");
        }
        if (!ragReady) {
            failureReasons.add("public_knowledge_index_" + indexStatus.toLowerCase(java.util.Locale.ROOT));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ready", ready);
        response.put("provider_ready", providerReady);
        response.put("rag_ready", ragReady);
        response.put("public_knowledge_rag_ready", ragReady);
        response.put("persistent_user_vector_store_enabled", false);
        response.put("embedding_provider_available", embeddingAvailable);
        response.put("capability_cards", cards);
        response.put("capability_vectors", vectors);
        response.put("recall_quality", recall);
        response.put("failure_reasons", failureReasons);
        response.put("failure_summary", ready
                ? "AI 供应商与公共知识向量索引均已就绪"
                : "基础面试仍可用；公共知识增强或 AI 供应商尚未完全就绪");
        return response;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static long number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
}
