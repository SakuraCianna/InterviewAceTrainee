package icu.sakuracianna.mianba.speech;

import tools.jackson.databind.ObjectMapper;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.platform.config.SecurityProperties;
import icu.sakuracianna.mianba.platform.config.SpeechProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 注册仅在 API 角色启用的实时语音 WebSocket 端点。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class SpeechWebSocketConfiguration implements WebSocketConfigurer {
    private final RealtimeAsrWebSocketHandler handler;
    private final SecurityProperties security;

    public SpeechWebSocketConfiguration(
            RealtimeAsrWebSocketHandler handler,
            SecurityProperties security) {
        this.handler = handler;
        this.security = security;
    }

    @Bean
    static TencentRealtimeAsrSigner tencentRealtimeAsrSigner(SpeechProperties properties, Clock clock) {
        return new TencentRealtimeAsrSigner(properties, clock);
    }

    @Bean
    static TencentCloudV3Signer tencentCloudV3Signer(SpeechProperties properties, Clock clock) {
        return new TencentCloudV3Signer(properties, clock);
    }

    @Bean
    static TencentTtsClient tencentTtsClient(
            ObjectMapper mapper,
            SpeechProperties properties,
            TencentCloudV3Signer signer) {
        return new TencentTtsClient(mapper, properties, signer);
    }

    @Bean
    static RealtimeAsrWebSocketHandler realtimeAsrWebSocketHandler(
            ObjectMapper mapper,
            InterviewService interviews,
            AbuseProtection abuseProtection,
            SpeechProperties properties,
            TencentRealtimeAsrSigner signer) {
        return new RealtimeAsrWebSocketHandler(mapper, interviews, abuseProtection, properties, signer);
    }

    /** 注册实时语音端点，并只允许显式配置的浏览器来源发起握手。 */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/speech/asr/*")
                .setAllowedOrigins(security.allowedOrigins().toArray(String[]::new));
    }
}
