package icu.sakuracianna.mianba.aiwork.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** 前端轮询与管理员监控共用的异步任务投影。 */
public record TaskView(
        UUID id,
        @JsonProperty("session_id") UUID sessionId,
        String kind,
        String status,
        String stage,
        int progress,
        int attempt,
        @JsonProperty("max_attempts") int maxAttempts,
        boolean retryable,
        long version,
        @JsonProperty("result_ref") Object resultRef,
        TaskError error,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    /** 可安全展示给用户或管理员的任务错误，不包含 Provider 原始响应。 */
    public record TaskError(String code, String message) {
    }
}
