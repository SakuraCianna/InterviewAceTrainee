package icu.sakuracianna.mianba.interview.domain;

/** 面试领域会话的合法生命周期状态。 */
public enum SessionStatus {
    ACTIVE,
    AWAITING_AI,
    COMPLETED,
    DELETED
}
