package icu.sakuracianna.mianba.platform.web;

import icu.sakuracianna.mianba.interview.material.MaterialParserClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    public HealthController(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            RabbitTemplate rabbit,
            Clock clock,
            MaterialParserClient materialParser) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.rabbit = rabbit;
        this.clock = clock;
        this.materialParser = materialParser;
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
     * 返回管理员可见的面试核心能力状态。
     * 向量表存在不等于 RAG 已实现，因此当前明确报告 {@code rag_not_implemented}。
     */
    @GetMapping("/api/health/interview-core")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> interviewCore() {
        long materialCount = count("SELECT count(*) FROM materials WHERE status = 'ready'");
        long vectorCount = count("SELECT count(*) FROM materials WHERE embedding IS NOT NULL AND status = 'ready'");
        long enabledProviders = count("SELECT count(*) FROM providers WHERE enabled = true");
        double coverage = materialCount == 0 ? 1.0 : (double) vectorCount / materialCount;

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("ready", true);
        cards.put("source_version", "boot4-ai2-v1");
        cards.put("source_policy", "database-products");
        cards.put("total_seed_count", 4);
        cards.put("counts_by_interview_type", Map.of("job", 1, "postgraduate", 1, "civil_service", 1, "ielts", 1));
        cards.put("expected_minimums", Map.of("job", 1, "postgraduate", 1, "civil_service", 1, "ielts", 1));
        cards.put("missing_preset_files", List.of());
        cards.put("duplicate_seed_ids", List.of());

        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("ready", false);
        vectors.put("enabled", false);
        vectors.put("status", "rag_not_implemented");
        vectors.put("table_name", "materials");
        vectors.put("table_exists", true);
        vectors.put("expected_seed_count", materialCount);
        vectors.put("total_vector_count", vectorCount);
        vectors.put("non_empty_vector_count", vectorCount);
        vectors.put("distinct_seed_count", vectorCount);
        vectors.put("coverage_rate", coverage);
        vectors.put("embedding_models", List.of());
        vectors.put("status_counts", List.of(Map.of("status", "ready", "count", materialCount)));
        vectors.put("missing_observation_columns", List.of());

        Map<String, Object> recall = Map.of(
                "ready", false,
                "enabled", false,
                "status", "rag_not_implemented",
                "probe_count", 0,
                "passed_probe_count", 0,
                "probes", List.of());
        boolean providerReady = enabledProviders > 0;
        boolean ready = false;
        List<String> failureReasons = new java.util.ArrayList<>();
        if (!providerReady) {
            failureReasons.add("no_enabled_ai_provider");
        }
        failureReasons.add("rag_pipeline_not_implemented");
        return Map.of(
                "ready", ready,
                "provider_ready", providerReady,
                "rag_ready", false,
                "capability_cards", cards,
                "capability_vectors", vectors,
                "recall_quality", recall,
                "failure_reasons", failureReasons,
                "failure_summary", providerReady
                        ? "基础面试 AI 可用，向量检索尚未启用"
                        : "没有启用的 AI 供应商，且向量检索尚未启用");
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
