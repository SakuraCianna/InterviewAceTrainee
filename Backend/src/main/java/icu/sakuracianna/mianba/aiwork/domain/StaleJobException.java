package icu.sakuracianna.mianba.aiwork.domain;

/** Worker 尝试提交已经被重试、取消或其他租约接管的旧任务版本。 */
public final class StaleJobException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StaleJobException(String message) {
        super(message);
    }
}
