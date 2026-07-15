package icu.sakuracianna.mianba.billing.domain;

/** 一次可审计、可幂等重放的余额变更结果。 */
public record CreditMutation(String idempotencyKey, int changeAmount, int balanceAfter, String reason) {
}
