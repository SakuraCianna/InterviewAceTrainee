package icu.sakuracianna.mianba.identity.redis;

import icu.sakuracianna.mianba.identity.service.OneTimeCodeStore;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;

/** 使用 Redis TTL 与 Lua 比对删除保证验证码只能成功消费一次。 */
public final class RedisOneTimeCodeStore implements OneTimeCodeStore {
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and current == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisOneTimeCodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void issue(String email, String code, Duration ttl) {
        try {
            redis.opsForValue().set(key(email), code, ttl);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public boolean consume(String email, String code) {
        try {
            Long result = redis.execute(CONSUME_SCRIPT, List.of(key(email)), code);
            return result != null && result == 1L;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public void revoke(String email, String code) {
        try {
            redis.execute(CONSUME_SCRIPT, List.of(key(email)), code);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private static String key(String email) {
        return "mianba:auth:email-code:" + email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "redis_unavailable", "验证码服务暂时不可用");
    }
}
