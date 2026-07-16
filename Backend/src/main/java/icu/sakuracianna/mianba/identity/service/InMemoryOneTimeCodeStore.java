package icu.sakuracianna.mianba.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 仅供显式本地降级与单元测试使用的一次性验证码表。 */
public final class InMemoryOneTimeCodeStore implements OneTimeCodeStore {
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryOneTimeCodeStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void issue(String email, String code, Duration ttl) {
        entries.put(normalize(email), new Entry(code, clock.instant().plus(ttl)));
    }

    @Override
    public boolean consume(String email, String code) {
        String key = normalize(email);
        Instant now = clock.instant();
        AtomicBoolean consumed = new AtomicBoolean(false);
        entries.compute(key, (ignored, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                return null;
            }
            if (current.code().equals(code)) {
                consumed.set(true);
                return null;
            }
            return current;
        });
        return consumed.get();
    }

    @Override
    public void revoke(String email, String code) {
        consume(email, code);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private record Entry(String code, Instant expiresAt) {
    }
}
