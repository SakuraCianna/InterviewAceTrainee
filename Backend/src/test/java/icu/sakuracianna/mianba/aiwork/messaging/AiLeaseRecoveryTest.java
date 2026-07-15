package icu.sakuracianna.mianba.aiwork.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AiLeaseRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @Test
    void retriesWhenLeaseExpiredButBudgetAndBusinessDeadlineRemain() {
        assertThat(AiLeaseRecovery.decide(1, 3, NOW.plusSeconds(300), NOW))
                .isEqualTo(AiLeaseRecovery.RecoveryAction.RETRY);
    }

    @Test
    void stopsWhenAttemptBudgetIsExhausted() {
        assertThat(AiLeaseRecovery.decide(3, 3, NOW.plusSeconds(300), NOW))
                .isEqualTo(AiLeaseRecovery.RecoveryAction.EXHAUSTED);
    }

    @Test
    void expiryTakesPrecedenceOverRetryBudget() {
        assertThat(AiLeaseRecovery.decide(1, 3, NOW, NOW))
                .isEqualTo(AiLeaseRecovery.RecoveryAction.EXPIRED);
    }

    @Test
    void exhaustedManualRetryIsNotAdvertisedAgain() {
        assertThat(AiLeaseRecovery.manualRetryAllowed(false, 0)).isTrue();
        assertThat(AiLeaseRecovery.manualRetryAllowed(false, 1)).isFalse();
        assertThat(AiLeaseRecovery.manualRetryAllowed(true, 0)).isFalse();
    }

    @Test
    void restoresTurnOnlyWhenLiveAwaitingSessionWasChanged() {
        UUID sessionId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE sessions"), eq("TASK_EXPIRED"), eq(sessionId)))
                .thenReturn(1);
        AiLeaseRecovery recovery = new AiLeaseRecovery(
                jdbc, mock(ObjectMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));

        recovery.restoreInterviewContext(sessionId, "TASK_EXPIRED");

        verify(jdbc).update(contains("expires_at > now()"), eq("TASK_EXPIRED"), eq(sessionId));
        verify(jdbc).update(contains("UPDATE turns"), eq(sessionId));
    }

    @Test
    void doesNotRestoreTurnWhenSessionIsClosedOrExpired() {
        UUID sessionId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiLeaseRecovery recovery = new AiLeaseRecovery(
                jdbc, mock(ObjectMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));

        recovery.restoreInterviewContext(sessionId, "TASK_EXPIRED");

        verify(jdbc, never()).update(contains("UPDATE turns"), eq(sessionId));
    }
}
