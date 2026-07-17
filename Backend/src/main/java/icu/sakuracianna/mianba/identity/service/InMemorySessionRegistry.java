package icu.sakuracianna.mianba.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 仅供显式本地降级与单元测试使用的进程内会话表。 */
public final class InMemorySessionRegistry implements SessionRegistry {
    private final Clock clock;
    private final Map<Key, Instant> sessions = new ConcurrentHashMap<>();

    public InMemorySessionRegistry(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void create(UUID userId, UUID sessionId, Duration ttl) {
        sessions.put(new Key(userId, sessionId), clock.instant().plus(ttl));
    }

    @Override
    public boolean isActive(UUID userId, UUID sessionId) {
        Instant expiry = sessions.get(new Key(userId, sessionId));
        return expiry != null && expiry.isAfter(clock.instant());
    }

    @Override
    public void revoke(UUID userId, UUID sessionId) {
        sessions.remove(new Key(userId, sessionId));
    }

    @Override
    public void revokeAll(UUID userId) {
        sessions.keySet().removeIf(key -> key.userId().equals(userId));
    }

    private record Key(UUID userId, UUID sessionId) {
    }
}
