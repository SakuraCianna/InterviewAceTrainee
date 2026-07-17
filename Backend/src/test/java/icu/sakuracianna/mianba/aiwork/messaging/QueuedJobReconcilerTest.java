package icu.sakuracianna.mianba.aiwork.messaging;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class QueuedJobReconcilerTest {

    @Test
    void restoresTurnOnlyAfterLiveAwaitingSessionTransition() {
        UUID sessionId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE sessions"), eq(sessionId))).thenReturn(1);
        QueuedJobReconciler reconciler = new QueuedJobReconciler(
                jdbc, mock(ObjectMapper.class), Clock.systemUTC());

        reconciler.restoreInterviewContext(sessionId);

        verify(jdbc).update(contains("expires_at > now()"), eq(sessionId));
        verify(jdbc).update(contains("UPDATE turns"), eq(sessionId));
    }

    @Test
    void leavesTurnUntouchedWhenSessionCannotTransition() {
        UUID sessionId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        QueuedJobReconciler reconciler = new QueuedJobReconciler(
                jdbc, mock(ObjectMapper.class), Clock.systemUTC());

        reconciler.restoreInterviewContext(sessionId);

        verify(jdbc, never()).update(contains("UPDATE turns"), eq(sessionId));
    }
}
