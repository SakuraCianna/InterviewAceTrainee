package icu.sakuracianna.mianba.identity.service;

import java.time.Duration;
import java.util.UUID;

/** JWT 之外的服务端会话注册表，支持单会话和全账号撤销。 */
public interface SessionRegistry {
    /** 创建带有效期的服务端会话，并加入用户会话索引。 */
    void create(UUID userId, UUID sessionId, Duration ttl);

    /** 判断指定会话是否仍处于有效期内且未被撤销。 */
    boolean isActive(UUID userId, UUID sessionId);

    /** 撤销单个会话。 */
    void revoke(UUID userId, UUID sessionId);

    /** 撤销用户的全部会话，通常用于改密或封禁账号。 */
    void revokeAll(UUID userId);
}
