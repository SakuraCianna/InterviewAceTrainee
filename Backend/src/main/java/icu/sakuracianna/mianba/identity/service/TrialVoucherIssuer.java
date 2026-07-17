package icu.sakuracianna.mianba.identity.service;

import java.util.UUID;

/** 注册事务内发放幂等试用券的计费端口。 */
public interface TrialVoucherIssuer {
    /**
     * 为新注册用户发放指定次数的体验券。
     * 实现必须以用户注册事件作为幂等边界。
     */
    void issueRegistrationVouchers(UUID userId, int quantity);
}
