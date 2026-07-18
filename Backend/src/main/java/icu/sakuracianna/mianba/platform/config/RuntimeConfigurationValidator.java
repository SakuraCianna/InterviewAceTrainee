package icu.sakuracianna.mianba.platform.config;

import icu.sakuracianna.mianba.identity.hcaptcha.HcaptchaProperties;
import icu.sakuracianna.mianba.interview.material.MaterialParserProperties;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** 在生产接受流量前验证当前运行角色的必需依赖和安全底线。 */
public final class RuntimeConfigurationValidator {
    private static final URI PRODUCTION_MATERIAL_PARSER_BASE_URL = URI.create("http://material-parser:8090");

    private RuntimeConfigurationValidator() {
    }

    /** 完整生产启动门禁，按 API、Worker、Migrate 三种运行角色验证各自依赖。 */
    public static void validate(
            RuntimeProperties runtime,
            SecurityProperties security,
            InfrastructureProperties infrastructure,
            IdentityProperties identity,
            SpeechProperties speech,
            AiRuntimeProperties ai,
            MaterialParserProperties materialParser,
            HcaptchaProperties hcaptcha,
            ContentSafetyProperties contentSafety) {
        if (!runtime.production()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        requireText(infrastructure.databaseUrl(), "mianba.infrastructure.database-url", missing);
        requireText(infrastructure.databaseUsername(), "mianba.infrastructure.database-username", missing);
        requireText(infrastructure.databasePassword(), "mianba.infrastructure.database-password", missing);
        if (runtime.localFallbackEnabled()) {
            missing.add("mianba.runtime.local-fallback-enabled must be false");
        }
        if (ai.stubEnabled()) {
            missing.add("mianba.ai-runtime.stub-enabled must be false");
        }
        if ((runtime.role().equals("api") || runtime.role().equals("worker"))
                && !contentSafety.hasStrongAuditKey()) {
            missing.add("mianba.content-safety.audit-hmac-secret must contain at least 48 characters");
        }
        if (runtime.role().equals("api")) {
            validateApiSecurity(security, missing);
            validateHcaptcha(hcaptcha, missing);
            if (identity.exposeDevCodes()) {
                missing.add("mianba.identity.expose-dev-codes must be false");
            }
            requireText(infrastructure.redisHost(), "mianba.infrastructure.redis-host", missing);
            validateRabbit(infrastructure, missing);
            requireText(identity.mailFrom(), "mianba.identity.mail-from", missing);
            requireText(identity.resendApiKey(), "mianba.identity.resend-api-key", missing);
            requireText(speech.tencentAppId(), "mianba.speech.tencent-app-id", missing);
            requireText(speech.tencentSecretId(), "mianba.speech.tencent-secret-id", missing);
            requireText(speech.tencentSecretKey(), "mianba.speech.tencent-secret-key", missing);
            if (!materialParser.hasStrongToken()) {
                missing.add("mianba.material-parser.token must contain at least 32 characters");
            }
            if (!PRODUCTION_MATERIAL_PARSER_BASE_URL.equals(materialParser.normalizedBaseUrl())) {
                missing.add("mianba.material-parser.base-url must equal http://material-parser:8090");
            }
        }
        if (runtime.role().equals("worker")) {
            validateRabbit(infrastructure, missing);
            requireText(ai.deepseekApiKey(), "spring.ai.deepseek.api-key", missing);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", missing));
        }
    }

    private static void validateApiSecurity(SecurityProperties security, List<String> missing) {
        if (security.jwtSecret().length() < 48) {
            missing.add("mianba.security.jwt-secret must contain at least 48 characters");
        }
        if (!security.cookieSecure()) {
            missing.add("mianba.security.cookie-secure must be true");
        }
        if (security.allowedOrigins().isEmpty()
                || security.allowedOrigins().stream().anyMatch(origin -> origin.equals("*"))) {
            missing.add("mianba.security.allowed-origins must be explicit");
        }
    }

    private static void validateHcaptcha(HcaptchaProperties hcaptcha, List<String> missing) {
        if (!hcaptcha.enabled()) {
            missing.add("mianba.hcaptcha.enabled must be true");
        }
        requireText(hcaptcha.siteKey(), "mianba.hcaptcha.site-key", missing);
        requireText(hcaptcha.secret(), "mianba.hcaptcha.secret", missing);
        if (!HcaptchaProperties.OFFICIAL_VERIFY_URL.equals(hcaptcha.verifyUrl())) {
            missing.add("mianba.hcaptcha.verify-url must equal https://api.hcaptcha.com/siteverify");
        }
    }

    private static void validateRabbit(InfrastructureProperties infrastructure, List<String> missing) {
        requireText(infrastructure.rabbitHost(), "mianba.infrastructure.rabbit-host", missing);
        requireText(infrastructure.rabbitUsername(), "mianba.infrastructure.rabbit-username", missing);
        requireText(infrastructure.rabbitPassword(), "mianba.infrastructure.rabbit-password", missing);
    }

    private static void requireText(String value, String name, List<String> missing) {
        if (value == null || value.isBlank()) {
            missing.add(name + " is required");
        }
    }
}
