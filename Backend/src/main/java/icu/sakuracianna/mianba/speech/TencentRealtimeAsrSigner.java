package icu.sakuracianna.mianba.speech;

import icu.sakuracianna.mianba.platform.config.SpeechProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 按腾讯云实时 ASR 专用 HMAC-SHA1 规则生成一次性 WebSocket 地址。 */
public final class TencentRealtimeAsrSigner {
    private static final String HOST = "asr.cloud.tencent.com";

    private final SpeechProperties properties;
    private final Clock clock;

    public TencentRealtimeAsrSigner(SpeechProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 每次连接都使用新的 voiceId 和 nonce；返回值含签名，调用方不得记录 URI。
     */
    public URI sign(String voiceId, String interviewType) {
        if (!properties.configured()) {
            throw new IllegalStateException("Tencent speech credentials are incomplete");
        }
        long timestamp = clock.instant().getEpochSecond();
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("engine_model_type", engine(interviewType));
        parameters.put("expired", Long.toString(timestamp + 3600));
        parameters.put("filter_dirty", "0");
        parameters.put("filter_modal", "0");
        parameters.put("filter_punc", "0");
        parameters.put("needvad", Integer.toString(properties.needVad()));
        parameters.put("nonce", Long.toString(ThreadLocalRandom.current().nextLong(100_000L, 10_000_000_000L)));
        parameters.put("secretid", properties.tencentSecretId());
        parameters.put("timestamp", Long.toString(timestamp));
        parameters.put("voice_format", "1");
        parameters.put("voice_id", voiceId);

        String unsignedQuery = query(parameters);
        String signingText = HOST + "/asr/v2/" + properties.tencentAppId() + '?' + unsignedQuery;
        parameters.put("signature", signature(signingText));
        return URI.create("wss://" + HOST + "/asr/v2/" + properties.tencentAppId() + '?' + query(parameters));
    }

    private String engine(String interviewType) {
        return "ielts".equalsIgnoreCase(interviewType) ? properties.ieltsEngine() : properties.defaultEngine();
    }

    private String signature(String signingText) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(properties.tencentSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(hmac.doFinal(signingText.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Unable to sign Tencent ASR request", exception);
        }
    }

    private static String query(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + '=' + encode(entry.getValue()))
                .reduce((left, right) -> left + '&' + right)
                .orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
