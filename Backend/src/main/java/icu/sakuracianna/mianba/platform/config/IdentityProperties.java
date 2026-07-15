package icu.sakuracianna.mianba.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 身份令牌、验证码有效期和邮件交付配置。 */
@ConfigurationProperties("mianba.identity")
public record IdentityProperties(
        int accessTokenMinutes,
        int emailCodeSeconds,
        boolean exposeDevCodes,
        String mailFrom,
        String resendApiKey) {

    @ConstructorBinding
    public IdentityProperties {
        if (accessTokenMinutes < 5) {
            accessTokenMinutes = 120;
        }
        if (emailCodeSeconds < 60) {
            emailCodeSeconds = 300;
        }
        mailFrom = mailFrom == null ? "" : mailFrom;
        resendApiKey = resendApiKey == null ? "" : resendApiKey;
    }

    public IdentityProperties(
            int accessTokenMinutes,
            int emailCodeSeconds,
            boolean exposeDevCodes,
            String mailFrom) {
        this(accessTokenMinutes, emailCodeSeconds, exposeDevCodes, mailFrom, "");
    }
}
