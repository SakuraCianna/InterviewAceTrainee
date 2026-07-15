package icu.sakuracianna.mianba.interview.material;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** API 到内部材料解析进程的固定地址、凭据和资源边界。 */
@ConfigurationProperties("mianba.material-parser")
public record MaterialParserProperties(
        URI baseUrl,
        String token,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxUploadBytes,
        int maxResponseBytes) {
    private static final URI DEFAULT_BASE_URL = URI.create("http://127.0.0.1:8090");

    public MaterialParserProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        token = token == null ? "" : token;
        if (token.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("mianba.material-parser.token contains control characters");
        }
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(2));
        requestTimeout = positive(requestTimeout, Duration.ofSeconds(8));
        maxUploadBytes = maxUploadBytes > 0
                ? Math.min(maxUploadBytes, MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES)
                : MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES;
        maxResponseBytes = maxResponseBytes > 0 ? Math.min(maxResponseBytes, 65_536) : 65_536;
    }

    /** 生产 token 至少使用 32 个不可预测字符，避免内部网络中的凭据猜测。 */
    public boolean hasStrongToken() {
        return token.length() >= 32;
    }

    /** 返回不包含尾部斜杠的固定解析服务根地址。 */
    public URI normalizedBaseUrl() {
        return baseUrl;
    }

    private static URI normalizeBaseUrl(URI value) {
        URI candidate = value == null ? DEFAULT_BASE_URL : value;
        String scheme = candidate.getScheme();
        if (!candidate.isAbsolute()
                || (!("http".equalsIgnoreCase(scheme)) && !("https".equalsIgnoreCase(scheme)))
                || candidate.getHost() == null
                || candidate.getUserInfo() != null
                || candidate.getQuery() != null
                || candidate.getFragment() != null
                || !(candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))) {
            throw new IllegalArgumentException("mianba.material-parser.base-url must be an HTTP service root");
        }
        String raw = candidate.toString();
        return URI.create(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
