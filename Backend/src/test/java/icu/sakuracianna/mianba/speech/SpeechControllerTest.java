package icu.sakuracianna.mianba.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.interview.service.SpeechContext;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeechControllerTest {

    @Test
    void ttsUsesCurrentServerQuestionForOwnedTurn() {
        InterviewService interviews = mock(InterviewService.class);
        TencentTtsClient tts = mock(TencentTtsClient.class);
        AbuseProtection abuse = mock(AbuseProtection.class);
        SpeechController controller = new SpeechController(interviews, tts, abuse);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "user@example.com", "user", UUID.randomUUID(), 0);
        when(interviews.requireSpeechContext(userId, sessionId))
                .thenReturn(new SpeechContext("job", 2, "服务端保存的问题"));
        when(tts.synthesize("服务端保存的问题", sessionId))
                .thenReturn(new TencentTtsClient.Result("audio", "audio/mpeg", "provider-request"));

        SpeechController.TtsResponse response = controller.synthesize(
                new SpeechController.TtsRequest(sessionId), user);

        assertThat(response.audioBase64()).isEqualTo("audio");
        verify(tts).synthesize("服务端保存的问题", sessionId);
        verify(abuse).check(eq("tts-user"), eq(userId.toString()), eq(12), any(Duration.class));
    }
}
