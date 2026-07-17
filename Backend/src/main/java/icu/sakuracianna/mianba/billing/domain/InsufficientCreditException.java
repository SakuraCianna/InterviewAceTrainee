package icu.sakuracianna.mianba.billing.domain;

/** 用户余额不足以完成本次扣减。 */
public final class InsufficientCreditException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InsufficientCreditException() {
        super("insufficient_credits");
    }
}
