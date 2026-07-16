package icu.sakuracianna.mianba.aiwork.domain;

import java.util.Objects;

/** 对外只暴露稳定错误码和是否可重试，不泄露 Provider 原始响应。 */
public record JobFailure(String code, boolean retryable) {
    public JobFailure {
        Objects.requireNonNull(code, "code");
    }
}
