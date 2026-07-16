package icu.sakuracianna.mianba.identity.redis;

import icu.sakuracianna.mianba.identity.service.SessionRegistry;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;

/**
 * Redis 会话注册表，通过用户级集合维护可撤销索引。
 * 实现不使用生产环境中可能阻塞实例的 {@code KEYS} 命令。
 */
public final class RedisSessionRegistry implements SessionRegistry {
    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            redis.call('SADD', KEYS[2], ARGV[2])
            local index_ttl = redis.call('PTTL', KEYS[2])
            local requested_ttl = tonumber(ARGV[1])
            if index_ttl < requested_ttl then
              redis.call('PEXPIRE', KEYS[2], requested_ttl)
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE_ALL_SCRIPT = new DefaultRedisScript<>("""
            local sessions = redis.call('SMEMBERS', KEYS[1])
            for _, session_id in ipairs(sessions) do
              redis.call('DEL', ARGV[1] .. session_id)
            end
            redis.call('DEL', KEYS[1])
            return #sessions
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisSessionRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void create(UUID userId, UUID sessionId, Duration ttl) {
        try {
            redis.execute(
                    CREATE_SCRIPT,
                    List.of(key(userId, sessionId), indexKey(userId)),
                    Long.toString(Math.max(1, ttl.toMillis())),
                    sessionId.toString());
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public boolean isActive(UUID userId, UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(userId, sessionId)));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public void revoke(UUID userId, UUID sessionId) {
        try {
            redis.execute(
                    REVOKE_SCRIPT,
                    List.of(key(userId, sessionId), indexKey(userId)),
                    sessionId.toString());
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public void revokeAll(UUID userId) {
        try {
            // 使用创建会话时维护的用户索引，避免生产 Redis 上使用阻塞全库的 KEYS 扫描。
            redis.execute(
                    REVOKE_ALL_SCRIPT,
                    List.of(indexKey(userId)),
                    "mianba:auth:session:" + userId + ':');
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private static String key(UUID userId, UUID sessionId) {
        return "mianba:auth:session:" + userId + ':' + sessionId;
    }

    private static String indexKey(UUID userId) {
        return "mianba:auth:user-sessions:" + userId;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "redis_unavailable", "认证服务暂时不可用");
    }
}
