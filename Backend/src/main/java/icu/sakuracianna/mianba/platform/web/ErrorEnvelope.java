package icu.sakuracianna.mianba.platform.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** 前后端稳定共享的 API 错误协议，使用 requestId 关联服务端日志。 */
public record ErrorEnvelope(
        String detail,
        String message,
        @JsonProperty("request_id") String requestId,
        List<FieldError> errors) {
    public ErrorEnvelope {
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(requestId, "requestId");
        errors = List.copyOf(errors == null ? List.of() : errors);
    }

    /** 创建错误信封，并由规范构造器统一处理空字段错误列表。 */
    public static ErrorEnvelope of(String detail, String message, String requestId, List<FieldError> errors) {
        return new ErrorEnvelope(detail, message, requestId, errors);
    }

    /** 单个请求字段的校验错误。 */
    public record FieldError(String field, String message) {
    }
}
