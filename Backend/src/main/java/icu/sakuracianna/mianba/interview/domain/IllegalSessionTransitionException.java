package icu.sakuracianna.mianba.interview.domain;

/** 请求的面试状态迁移不满足领域规则。 */
public final class IllegalSessionTransitionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IllegalSessionTransitionException(String message) {
        super(message);
    }
}
