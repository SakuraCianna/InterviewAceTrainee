package icu.sakuracianna.mianba.aiwork.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * API 与 Worker 之间的稳定 AI 任务信封。
 *
 * 字段名显式固定为 snake_case，避免全局 Jackson 命名策略与手写 Map 键不一致时，
 * Worker 将合法消息误判为畸形消息。
 */
public record AiJobEnvelope(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("message_id") UUID messageId,
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("created_at") Instant createdAt) {

    public AiJobEnvelope {
        if (schemaVersion != 1 || messageId == null || jobId == null || createdAt == null) {
            throw new IllegalArgumentException("Invalid AI job envelope");
        }
        correlationId = normalize(correlationId, "unknown");
        traceId = normalize(traceId, correlationId);
    }

    /** 创建一个使用全新消息 ID 的 v1 信封。 */
    public static AiJobEnvelope create(
            UUID jobId,
            String correlationId,
            String traceId,
            Instant createdAt) {
        return new AiJobEnvelope(1, UUID.randomUUID(), jobId, correlationId, traceId, createdAt);
    }

    private static String normalize(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }
}
