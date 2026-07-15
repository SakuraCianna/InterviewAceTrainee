package icu.sakuracianna.mianba.billing.domain;

/** 余额聚合版本已被并发请求推进，当前变更不得覆盖。 */
public final class StaleCreditVersionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StaleCreditVersionException() {
        super("credit_version_stale");
    }
}
