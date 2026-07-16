package icu.sakuracianna.mianba.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.interview.service.SpeechContext;
import icu.sakuracianna.mianba.platform.config.SpeechProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class RealtimeAsrWebSocketHandlerTest {

    @Test
    void startedSilentConnectionReleasesCapacityAndTurnImmediately() throws Exception {
        Fixture fixture = fixture();
        WebSocketSession first = fixture.session("client-1");
        fixture.handler.afterConnectionEstablished(first);
        fixture.handler.handleTextMessage(first, startMessage());
        assertThat(fixture.handler.availableCapacity()).isZero();

        fixture.now.set(RealtimeAsrWebSocketHandler.CLIENT_IDLE_TIMEOUT_NANOS + 1);
        fixture.handler.expireIdleConnections();

        assertThat(fixture.handler.availableCapacity()).isEqualTo(1);
        verify(first).sendMessage(anyMessageContaining("asr_idle_timeout"));
        verify(first).close(any(CloseStatus.class));

        WebSocketSession reconnect = fixture.session("client-2");
        fixture.handler.afterConnectionEstablished(reconnect);
        verify(reconnect, never()).close(any(CloseStatus.class));
        fixture.handler.stopIdleWatchdog();
    }

    @Test
    void absoluteWallClockLimitCannotBeExtendedBySilentConnection() throws Exception {
        Fixture fixture = fixture();
        WebSocketSession session = fixture.session("client-absolute");
        fixture.handler.afterConnectionEstablished(session);
        fixture.handler.handleTextMessage(session, startMessage());

        fixture.now.set(Duration.ofSeconds(330).toNanos() + 1);
        fixture.handler.expireIdleConnections();

        assertThat(fixture.handler.availableCapacity()).isEqualTo(1);
        verify(session).sendMessage(anyMessageContaining("asr_session_timeout"));
        verify(session, never()).sendMessage(anyMessageContaining("asr_idle_timeout"));
        fixture.handler.stopIdleWatchdog();
    }

    private static Fixture fixture() {
        AtomicLong now = new AtomicLong();
        InterviewService interviews = mock(InterviewService.class);
        AbuseProtection abuse = mock(AbuseProtection.class);
        TencentRealtimeAsrSigner signer = mock(TencentRealtimeAsrSigner.class);
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder webSocketBuilder = mock(WebSocket.Builder.class);
        ScheduledExecutorService watchdog = mock(ScheduledExecutorService.class);
        SpeechProperties properties = new SpeechProperties(
                "123456", "secret-id", "secret-key", "16k_zh_en", "16k_en",
                List.of(603006), 1, 300, 1);
        UUID userId = UUID.randomUUID();
        UUID interviewId = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "user@example.com", "user", UUID.randomUUID(), 0);

        when(interviews.requireSpeechContext(userId, interviewId))
                .thenReturn(new SpeechContext("job", 1, "question"));
        when(signer.sign(anyString(), anyString())).thenReturn(URI.create("wss://asr.example.test/stream"));
        when(httpClient.newWebSocketBuilder()).thenReturn(webSocketBuilder);
        when(webSocketBuilder.connectTimeout(any(Duration.class))).thenReturn(webSocketBuilder);
        when(webSocketBuilder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                .thenReturn(new CompletableFuture<>());

        RealtimeAsrWebSocketHandler handler = new RealtimeAsrWebSocketHandler(
                new tools.jackson.databind.ObjectMapper(), interviews, abuse, properties, signer,
                httpClient, watchdog, now::get);
        return new Fixture(handler, now, user, interviewId);
    }

    private static TextMessage startMessage() {
        return new TextMessage("{\"type\":\"start\",\"sample_rate\":16000}");
    }

    private static WebSocketMessage<?> anyMessageContaining(String expected) {
        return org.mockito.ArgumentMatchers.argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains(expected));
    }

    private record Fixture(
            RealtimeAsrWebSocketHandler handler,
            AtomicLong now,
            AuthenticatedUser user,
            UUID interviewId) {

        private WebSocketSession session(String id) {
            WebSocketSession session = mock(WebSocketSession.class);
            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(user);
            when(session.getPrincipal()).thenReturn(authentication);
            when(session.getId()).thenReturn(id);
            when(session.getUri()).thenReturn(URI.create("wss://example.test/ws/asr/" + interviewId));
            when(session.isOpen()).thenReturn(true);
            return session;
        }
    }
}
