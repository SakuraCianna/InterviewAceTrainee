package icu.sakuracianna.mianba.identity.redis;

import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;

/** 使用 Redis 有序集合实现跨 API 实例共享的滑动窗口限流。 */
public final class RedisAbuseProtection implements AbuseProtection {
    private static final DefaultRedisScript<Long> SLIDING_WINDOW = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, now, ARGV[4])
            redis.call('PEXPIRE', key, window)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisAbuseProtection(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void check(String action, String subject, int limit, Duration window) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate limit and window must be positive");
        }
        // 限流主体可能是邮箱或 IP；只把摘要写入 Redis，降低缓存泄漏时的个人信息暴露面。
        String key = "mianba:rate:" + safeAction(action) + ':' + sha256(subject == null ? "unknown" : subject);
        long now = clock.millis();
        try {
            Long allowed = redis.execute(
                    SLIDING_WINDOW,
                    List.of(key),
                    Long.toString(now),
                    Long.toString(window.toMillis()),
                    Integer.toString(limit),
                    now + "-" + UUID.randomUUID());
            if (!Long.valueOf(1L).equals(allowed)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "rate_limit_exceeded", "请求过于频繁，请稍后重试");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            // 认证、验证码、材料上传等高风险或高成本入口统一 fail-closed，避免 Redis 故障时失去防护。
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "abuse_protection_unavailable", "安全校验暂时不可用，请稍后重试");
        }
    }

    private static String safeAction(String action) {
        String value = action == null ? "unknown" : action.replaceAll("[^a-z0-9_-]", "_");
        return value.length() <= 48 ? value : value.substring(0, 48);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
