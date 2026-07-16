package icu.sakuracianna.mianba.identity.security;

import java.util.UUID;

/** 经 JWT、Redis 会话和数据库认证版本三重验证后写入 Spring Security 的用户主体。 */
public record AuthenticatedUser(UUID userId, String email, String role, UUID sessionId, long authVersion) {
    /** 判断当前主体是否具有管理员角色。 */
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
