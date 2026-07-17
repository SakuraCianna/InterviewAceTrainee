package icu.sakuracianna.mianba.identity.hcaptcha;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** hCaptcha 服务端校验所需的固定端点、凭据和网络资源边界。 */
@ConfigurationProperties("mianba.hcaptcha")
public record HcaptchaProperties(
        boolean enabled,
        String siteKey,
        String secret,
        URI verifyUrl,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxResponseBytes) {
    public static final URI OFFICIAL_VERIFY_URL = URI.create("https://api.hcaptcha.com/siteverify");
    public static final int HARD_MAX_RESPONSE_BYTES = 16 * 1024;

    public HcaptchaProperties {
        siteKey = safeCredential(siteKey, "site-key");
        secret = safeCredential(secret, "secret");
        verifyUrl = safeVerifyUrl(verifyUrl);
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(2));
        requestTimeout = positive(requestTimeout, Duration.ofSeconds(5));
        maxResponseBytes = maxResponseBytes > 0
                ? Math.min(maxResponseBytes, HARD_MAX_RESPONSE_BYTES)
                : HARD_MAX_RESPONSE_BYTES;
    }

    /** 判断启用状态下是否具备调用上游所需的两个凭据。 */
    public boolean hasCredentials() {
        return !siteKey.isBlank() && !secret.isBlank();
    }

    /** 返回可安全写入诊断日志的配置摘要，不暴露站点标识或服务端密钥。 */
    @Override
    public String toString() {
        return "HcaptchaProperties[enabled=" + enabled
                + ", siteKeyConfigured=" + !siteKey.isBlank()
                + ", secretConfigured=" + !secret.isBlank()
                + ", verifyUrl=" + verifyUrl
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }

    private static String safeCredential(String value, String name) {
        String candidate = value == null ? "" : value;
        if (candidate.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("mianba.hcaptcha." + name + " contains control characters");
        }
        return candidate.strip();
    }

    private static URI safeVerifyUrl(URI value) {
        URI candidate = value == null ? OFFICIAL_VERIFY_URL : value;
        String scheme = candidate.getScheme();
        if (!candidate.isAbsolute()
                || (!("http".equalsIgnoreCase(scheme)) && !("https".equalsIgnoreCase(scheme)))
                || candidate.getHost() == null
                || candidate.getUserInfo() != null
                || candidate.getQuery() != null
                || candidate.getFragment() != null) {
            throw new IllegalArgumentException("mianba.hcaptcha.verify-url must be an absolute HTTP endpoint");
        }
        return candidate;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
