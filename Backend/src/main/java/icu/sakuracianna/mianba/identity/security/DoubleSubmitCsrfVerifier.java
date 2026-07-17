package icu.sakuracianna.mianba.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 以常量时间比较 Cookie 与请求头中的双提交 CSRF Token。 */
public final class DoubleSubmitCsrfVerifier {
    /**
     * 以常量时间比较两个令牌。
     *
     * @return 两个值均存在且字节完全一致时返回 {@code true}
     */
    public boolean matches(String cookieValue, String headerValue) {
        if (cookieValue == null || headerValue == null) {
            return false;
        }
        return MessageDigest.isEqual(
                cookieValue.getBytes(StandardCharsets.UTF_8),
                headerValue.getBytes(StandardCharsets.UTF_8));
    }
}
