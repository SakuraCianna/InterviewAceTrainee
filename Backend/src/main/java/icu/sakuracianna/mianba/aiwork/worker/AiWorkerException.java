package icu.sakuracianna.mianba.aiwork.worker;

/** 带有稳定错误码与可重试语义的 Worker 执行异常。 */
public final class AiWorkerException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final boolean retryable;

    public AiWorkerException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public AiWorkerException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
