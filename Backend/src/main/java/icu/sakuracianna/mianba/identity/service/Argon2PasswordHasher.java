package icu.sakuracianna.mianba.identity.service;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * 使用 Argon2id 生成带盐密码哈希并进行常量时间验证。
 * Argon2 属于高内存成本操作，公平信号量限制并发量，防止恶意登录请求耗尽 4 GB 主机内存。
 */
public final class Argon2PasswordHasher implements PasswordHasher {
    private final PasswordEncoder delegate;
    private final Semaphore capacity;
    private final Duration acquireTimeout;

    /** 使用生产默认参数与两路并发上限创建密码哈希器。 */
    public Argon2PasswordHasher() {
        this(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), 2, Duration.ofMillis(75));
    }

    Argon2PasswordHasher(PasswordEncoder delegate, int maxConcurrency, Duration acquireTimeout) {
        if (delegate == null || maxConcurrency < 1 || acquireTimeout == null || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("Invalid password hashing capacity configuration");
        }
        this.delegate = delegate;
        this.capacity = new Semaphore(maxConcurrency, true);
        this.acquireTimeout = acquireTimeout;
    }

    @Override
    public String hash(String rawPassword) {
        return withCapacity(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return withCapacity(() -> delegate.matches(rawPassword, encodedPassword));
    }

    private <T> T withCapacity(Supplier<T> operation) {
        boolean acquired;
        try {
            acquired = capacity.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "auth_capacity_full", "认证服务繁忙，请稍后重试");
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "auth_capacity_full", "认证服务繁忙，请稍后重试");
        }
        try {
            return operation.get();
        } finally {
            // 编码器异常也必须释放配额，否则少量异常请求即可永久耗尽认证容量。
            capacity.release();
        }
    }
}
