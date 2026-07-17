package icu.sakuracianna.mianba.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryOneTimeCodeStoreTest {

    @Test
    void codeCanBeConsumedExactlyOnce() {
        InMemoryOneTimeCodeStore store = new InMemoryOneTimeCodeStore(
                Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC));
        store.issue("user@example.com", "123456", Duration.ofMinutes(5));

        assertThat(store.consume("USER@example.com", "123456")).isTrue();
        assertThat(store.consume("user@example.com", "123456")).isFalse();
        assertThat(store.consume("never-issued@example.com", "123456")).isFalse();
    }

    @Test
    void revokeOnlyRemovesTheMatchingCode() {
        InMemoryOneTimeCodeStore store = new InMemoryOneTimeCodeStore(
                Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC));
        store.issue("user@example.com", "654321", Duration.ofMinutes(5));

        store.revoke("user@example.com", "123456");
        assertThat(store.consume("user@example.com", "654321")).isTrue();
    }
}
