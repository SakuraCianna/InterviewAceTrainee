package icu.sakuracianna.mianba.platform.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 腾讯云语音桥接配置；三个凭据字段必须由生产 ConfigTree secret 注入。 */
@ConfigurationProperties("mianba.speech")
public record SpeechProperties(
        String tencentAppId,
        String tencentSecretId,
        String tencentSecretKey,
        String defaultEngine,
        String ieltsEngine,
        List<Integer> ttsVoiceTypes,
        int needVad,
        int maxSeconds,
        int maxConcurrentSessions) {

    public SpeechProperties {
        tencentAppId = text(tencentAppId);
        tencentSecretId = text(tencentSecretId);
        tencentSecretKey = text(tencentSecretKey);
        defaultEngine = defaultEngine == null || defaultEngine.isBlank() ? "16k_zh_en" : defaultEngine.trim();
        ieltsEngine = ieltsEngine == null || ieltsEngine.isBlank() ? "16k_en" : ieltsEngine.trim();
        List<Integer> validVoiceTypes = ttsVoiceTypes == null
                ? List.of()
                : ttsVoiceTypes.stream().filter(voice -> voice != null && voice > 0).distinct().toList();
        // 配置项可能“存在但全部非法”；这里仍需回退，否则稳定选音色时会对空列表取模。
        ttsVoiceTypes = validVoiceTypes.isEmpty() ? List.of(603006) : List.copyOf(validVoiceTypes);
        needVad = needVad == 0 ? 0 : 1;
        maxSeconds = maxSeconds > 0 ? Math.min(maxSeconds, 600) : 300;
        maxConcurrentSessions = maxConcurrentSessions > 0 ? Math.min(maxConcurrentSessions, 8) : 2;
    }

    /** 返回生产启动校验所需的凭据是否完整。 */
    public boolean configured() {
        return !tencentAppId.isBlank() && !tencentSecretId.isBlank() && !tencentSecretKey.isBlank();
    }

    /** 同一面试会话稳定选择一个音色，避免多轮问题之间出现声音跳变。 */
    public int voiceType(UUID sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.toString().getBytes(StandardCharsets.UTF_8));
            return ttsVoiceTypes.get(Math.floorMod(java.nio.ByteBuffer.wrap(digest).getInt(), ttsVoiceTypes.size()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
