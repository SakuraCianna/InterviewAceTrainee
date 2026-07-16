package icu.sakuracianna.mianba.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
    private final JwtService service = new JwtService("test-secret-that-is-at-least-thirty-two-bytes", clock);

    @Test
    void tokenCarriesSubjectRoleSessionAndExpiry() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token = service.issue(userId, "user@example.com", "admin", sessionId, 7, Duration.ofMinutes(30));
        AuthenticatedUser claims = service.verify(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.email()).isEqualTo("user@example.com");
        assertThat(claims.role()).isEqualTo("admin");
        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.authVersion()).isEqualTo(7);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = service.issue(UUID.randomUUID(), "user@example.com", "user", UUID.randomUUID(), 0,
                Duration.ofMinutes(30));

        assertThatThrownBy(() -> service.verify(token.substring(0, token.length() - 2) + "aa"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
