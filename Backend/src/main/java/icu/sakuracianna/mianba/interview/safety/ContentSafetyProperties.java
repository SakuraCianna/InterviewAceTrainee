package icu.sakuracianna.mianba.interview.safety;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 内容风控审计的独立密钥配置，不与 JWT 或外部服务凭据复用。 */
@ConfigurationProperties("mianba.content-safety")
public record ContentSafetyProperties(String auditHmacSecret) {
    public ContentSafetyProperties {
        auditHmacSecret = auditHmacSecret == null ? "" : auditHmacSecret;
    }

    public boolean hasStrongAuditKey() {
        return auditHmacSecret.length() >= 48;
    }
}
