package icu.sakuracianna.mianba.identity.service;

import java.util.UUID;

/** 认证流程使用的最小用户账号快照。 */
public record UserAccount(
        UUID id,
        String email,
        String passwordHash,
        String role,
        int creditBalance,
        boolean active,
        long authVersion) {
}
