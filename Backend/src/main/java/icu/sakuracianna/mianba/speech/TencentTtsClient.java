package icu.sakuracianna.mianba.speech;

import icu.sakuracianna.mianba.platform.config.SpeechProperties;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 有界调用腾讯云基础语音合成 API，返回 MP3 Base64 数据。 */
public final class TencentTtsClient {
    private static final String HOST = "tts.tencentcloudapi.com";
    private static final int MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024;

    private final ObjectMapper mapper;
    private final SpeechProperties properties;
    private final TencentCloudV3Signer signer;
    private final HttpClient httpClient;
    private final Semaphore capacity = new Semaphore(1, true);

    public TencentTtsClient(
            ObjectMapper mapper,
            SpeechProperties properties,
            TencentCloudV3Signer signer) {
        this.mapper = mapper;
        this.properties = properties;
        this.signer = signer;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 合成一段短问题文本；原始腾讯错误消息不会透传给客户端。 */
    public Result synthesize(String text, UUID sessionId) {
        if (!capacity.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "tts_capacity_full", "语音合成服务繁忙，请稍后重试");
        }
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "Text", text,
                    "SessionId", sessionId.toString(),
                    "ModelType", 1,
                    "VoiceType", properties.voiceType(sessionId),
                    "Codec", "mp3"));
            TencentCloudV3Signer.SignedHeaders headers = signer.sign(HOST, "tts", payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + HOST + '/'))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", headers.authorization())
                    .header("Content-Type", headers.contentType())
                    .header("X-TC-Action", "TextToVoice")
                    .header("X-TC-Version", "2019-08-23")
                    .header("X-TC-Timestamp", headers.timestamp())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream body = response.body()) {
                responseBytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBytes.length > MAX_RESPONSE_BYTES || response.statusCode() / 100 != 2) {
                throw unavailable();
            }
            JsonNode envelope = mapper.readTree(responseBytes);
            JsonNode body = envelope.path("Response");
            if (!body.path("Error").isMissingNode()) {
                throw unavailable();
            }
            String audio = body.path("Audio").stringValue("").trim();
            if (audio.isEmpty() || audio.length() > MAX_RESPONSE_BYTES || !validBase64(audio)) {
                throw unavailable();
            }
            return new Result(audio, "audio/mpeg", body.path("RequestId").stringValue(""));
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        } finally {
            capacity.release();
        }
    }

    private static boolean validBase64(String value) {
        try (InputStream decoded = Base64.getDecoder().wrap(
                new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)))) {
            decoded.transferTo(OutputStream.nullOutputStream());
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "tts_provider_failed", "语音合成暂时不可用");
    }

    /** 语音合成成功结果。 */
    public record Result(String audioBase64, String mimeType, String providerRequestId) {
    }
}
