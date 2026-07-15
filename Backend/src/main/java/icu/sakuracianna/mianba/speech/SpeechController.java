package icu.sakuracianna.mianba.speech;

import com.fasterxml.jackson.annotation.JsonProperty;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.interview.service.SpeechContext;
import icu.sakuracianna.mianba.platform.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 面试语音 HTTP 接口；实时 ASR 使用独立 WebSocket 处理器。 */
@RestController
@RequestMapping("/api/speech")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class SpeechController {
    private final InterviewService interviews;
    private final TencentTtsClient tts;
    private final AbuseProtection abuseProtection;

    public SpeechController(
            InterviewService interviews,
            TencentTtsClient tts,
            AbuseProtection abuseProtection) {
        this.interviews = interviews;
        this.tts = tts;
        this.abuseProtection = abuseProtection;
    }

    /**
     * 合成服务端当前问题的语音。
     * 客户端不能提交任意文本，避免该接口被滥用为通用付费语音代理。
     */
    @PostMapping("/tts")
    TtsResponse synthesize(
            @Valid @RequestBody TtsRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        SpeechContext context = interviews.requireSpeechContext(user.userId(), request.sessionId());
        if (context.questionText().length() > 500) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "tts_question_too_long", "当前问题过长，请使用浏览器朗读");
        }
        Duration window = Duration.ofMinutes(10);
        abuseProtection.check("tts-user", user.userId().toString(), 12, window);
        abuseProtection.check(
                "tts-turn",
                user.userId() + ":" + request.sessionId() + ':' + context.turnIndex(),
                3,
                window);
        TencentTtsClient.Result result = tts.synthesize(context.questionText(), request.sessionId());
        return new TtsResponse(
                result.audioBase64(), result.mimeType(), "tencent", result.providerRequestId());
    }

    /** 语音合成请求只携带会话标识，问题正文和训练类型均由服务端当前轮次决定。 */
    public record TtsRequest(@NotNull @JsonProperty("session_id") UUID sessionId) {
    }

    /** 与现有前端兼容的语音合成响应。 */
    public record TtsResponse(
            @JsonProperty("audio_base64") String audioBase64,
            @JsonProperty("mime_type") String mimeType,
            @JsonProperty("provider_id") String providerId,
            @JsonProperty("provider_request_id") String providerRequestId) {
    }
}
