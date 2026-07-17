package icu.sakuracianna.mianba.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT、Cookie 与 CORS 所需的安全配置。 */
@ConfigurationProperties("mianba.security")
public record SecurityProperties(
        String jwtSecret,
        boolean cookieSecure,
        String cookieSameSite,
        List<String> allowedOrigins) {

    public SecurityProperties {
        jwtSecret = jwtSecret == null ? "" : jwtSecret;
        cookieSameSite = cookieSameSite == null || cookieSameSite.isBlank() ? "Lax" : cookieSameSite;
        allowedOrigins = List.copyOf(allowedOrigins == null ? List.of() : allowedOrigins);
    }
}
