package icu.sakuracianna.mianba.speech;

import static org.assertj.core.api.Assertions.assertThat;

import icu.sakuracianna.mianba.platform.config.SpeechProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TencentRealtimeAsrSignerTest {

    @Test
    void signsSortedParametersAndSelectsIeltsEngine() throws Exception {
        SpeechProperties properties = new SpeechProperties(
                "123456", "secret-id", "secret-key", "16k_zh_en", "16k_en",
                List.of(603006), 1, 300, 2);
        Clock clock = Clock.fixed(Instant.parse("2026-07-14T06:00:00Z"), ZoneOffset.UTC);
        URI uri = new TencentRealtimeAsrSigner(properties, clock).sign("voice-1", "ielts");
        Map<String, String> parameters = query(uri.getRawQuery());

        assertThat(uri.getScheme()).isEqualTo("wss");
        assertThat(uri.getHost()).isEqualTo("asr.cloud.tencent.com");
        assertThat(parameters)
                .containsEntry("engine_model_type", "16k_en")
                .containsEntry("voice_id", "voice-1")
                .containsEntry("timestamp", "1784008800")
                .containsEntry("expired", "1784012400");

        String signature = parameters.remove("signature");
        String unsignedQuery = parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + '=' + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("secret-key".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(
                ("asr.cloud.tencent.com/asr/v2/123456?" + unsignedQuery).getBytes(StandardCharsets.UTF_8)));
        assertThat(signature).isEqualTo(expected);
    }

    private static Map<String, String> query(String rawQuery) {
        return Arrays.stream(rawQuery.split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> decode(pair[1]),
                        (first, second) -> second,
                        TreeMap::new));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
