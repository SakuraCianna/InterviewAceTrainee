package icu.sakuracianna.mianba.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 身份入口限流阈值；生产默认值按单用户低并发场景设置。 */
@ConfigurationProperties("mianba.abuse")
public record AbuseProtectionProperties(
        int loginIpLimit,
        int loginEmailLimit,
        int adminLoginLimit,
        int emailCodeIpLimit,
        int emailCodeEmailLimit,
        int windowSeconds) {

    public AbuseProtectionProperties {
        loginIpLimit = positive(loginIpLimit, 20);
        loginEmailLimit = positive(loginEmailLimit, 8);
        adminLoginLimit = positive(adminLoginLimit, 5);
        emailCodeIpLimit = positive(emailCodeIpLimit, 5);
        emailCodeEmailLimit = positive(emailCodeEmailLimit, 3);
        windowSeconds = positive(windowSeconds, 600);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
