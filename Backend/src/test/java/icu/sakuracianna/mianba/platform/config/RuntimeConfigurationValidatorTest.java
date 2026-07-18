package icu.sakuracianna.mianba.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import icu.sakuracianna.mianba.identity.hcaptcha.HcaptchaProperties;
import icu.sakuracianna.mianba.interview.material.MaterialParserProperties;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeConfigurationValidatorTest {

    @Test
    void productionApiRejectsWeakSecretAndInsecureCookie() {
        RuntimeProperties runtime = new RuntimeProperties("api", true, false);
        SecurityProperties security = new SecurityProperties(
                "short", false, "Lax", List.of("https://app.example.com"));

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(), new AiRuntimeProperties("", false),
                materialParser(), hcaptcha(), contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret")
                .hasMessageContaining("cookie-secure");
    }

    @Test
    void productionApiRejectsLocalFallbackAndDevelopmentCodeExposure() {
        RuntimeProperties runtime = new RuntimeProperties("api", true, true);
        SecurityProperties security = new SecurityProperties(
                "x".repeat(48), true, "Strict", List.of("https://app.example.com"));
        IdentityProperties unsafeIdentity = new IdentityProperties(
                120, 300, true, "from@example.com", "resend-key");

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), unsafeIdentity, speech(), new AiRuntimeProperties("", false),
                materialParser(), hcaptcha(), contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-fallback-enabled")
                .hasMessageContaining("expose-dev-codes");
    }

    @Test
    void productionMigrationOnlyRequiresDatabaseCredentials() {
        RuntimeProperties runtime = new RuntimeProperties("migrate", true, false);
        InfrastructureProperties databaseOnly = new InfrastructureProperties(
                "jdbc:postgresql://db/mianba", "owner", "database-password",
                "", "", "", "");

        assertThatCode(() -> RuntimeConfigurationValidator.validate(
                runtime,
                new SecurityProperties("", false, "Lax", List.of()),
                databaseOnly,
                new IdentityProperties(120, 300, false, "", ""),
                new SpeechProperties("", "", "", "16k_zh_en", "16k_en", List.of(603006), 1, 300, 2),
                new AiRuntimeProperties("", false),
                new MaterialParserProperties(null, "", null, null, 0, 0),
                disabledHcaptcha(), contentSafety()))
                .doesNotThrowAnyException();
    }

    @Test
    void completeProductionWorkerRejectsMissingDeepSeekKey() {
        RuntimeProperties runtime = new RuntimeProperties("worker", true, false);
        SecurityProperties security = new SecurityProperties(
                "x".repeat(48), true, "Strict", List.of("https://app.example.com"));
        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(), new AiRuntimeProperties("", false),
                materialParser(), hcaptcha(), contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.ai.deepseek.api-key");
    }

    @Test
    void productionWorkerRejectsMissingAuditHmacKey() {
        RuntimeProperties runtime = new RuntimeProperties("worker", true, false);
        SecurityProperties security = new SecurityProperties(
                "", true, "Strict", List.of("https://app.example.com"));

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(),
                new AiRuntimeProperties("deepseek-test-key", false), materialParser(), hcaptcha(),
                new ContentSafetyProperties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mianba.content-safety.audit-hmac-secret");
    }

    @Test
    void productionRejectsDeterministicAiStubForEveryRuntimeRole() {
        RuntimeProperties runtime = new RuntimeProperties("migrate", true, false);

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime,
                new SecurityProperties("", false, "Lax", List.of()),
                infrastructure(),
                identity(),
                speech(),
                new AiRuntimeProperties("", true),
                materialParser(), hcaptcha(), contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mianba.ai-runtime.stub-enabled must be false");
    }

    @Test
    void productionApiRejectsMissingMaterialParserToken() {
        RuntimeProperties runtime = new RuntimeProperties("api", true, false);
        SecurityProperties security = new SecurityProperties(
                "x".repeat(48), true, "Strict", List.of("https://app.example.com"));
        MaterialParserProperties missingToken = new MaterialParserProperties(
                URI.create("http://material-parser:8090"), "", Duration.ofSeconds(1),
                Duration.ofSeconds(8), 5 * 1024 * 1024, 65_536);

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(), new AiRuntimeProperties("", false),
                missingToken, hcaptcha(), contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mianba.material-parser.token");
    }

    @Test
    void productionApiRejectsMaterialParserOutsideFixedInternalService() {
        RuntimeProperties runtime = new RuntimeProperties("api", true, false);
        SecurityProperties security = new SecurityProperties(
                "x".repeat(48), true, "Strict", List.of("https://app.example.com"));
        MaterialParserProperties loopback = new MaterialParserProperties(
                URI.create("http://127.0.0.1:8090"), "p".repeat(32), Duration.ofSeconds(1),
                Duration.ofSeconds(8), 5 * 1024 * 1024, 65_536);

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(), new AiRuntimeProperties("", false),
                loopback, hcaptcha(), contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mianba.material-parser.base-url");
    }

    @Test
    void nonProductionApiAllowsLoopbackMaterialParser() {
        RuntimeProperties runtime = new RuntimeProperties("api", false, false);
        MaterialParserProperties loopback = new MaterialParserProperties(
                URI.create("http://127.0.0.1:8090"), "", Duration.ofSeconds(1),
                Duration.ofSeconds(8), 5 * 1024 * 1024, 65_536);

        assertThatCode(() -> RuntimeConfigurationValidator.validate(
                runtime,
                new SecurityProperties("", false, "Lax", List.of()),
                new InfrastructureProperties("", "", "", "", "", "", ""),
                new IdentityProperties(120, 300, true, "", ""),
                new SpeechProperties("", "", "", "16k_zh_en", "16k_en", List.of(603006), 1, 300, 2),
                new AiRuntimeProperties("", true),
                loopback,
                disabledHcaptcha(), contentSafety()))
                .doesNotThrowAnyException();
    }

    @Test
    void completeProductionApiAcceptsFixedInternalMaterialParser() {
        RuntimeProperties runtime = new RuntimeProperties("api", true, false);
        SecurityProperties security = new SecurityProperties(
                "x".repeat(48), true, "Strict", List.of("https://app.example.com"));

        assertThatCode(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(), new AiRuntimeProperties("", false),
                materialParser(), hcaptcha(), contentSafety()))
                .doesNotThrowAnyException();
    }

    @Test
    void productionApiRejectsDisabledHcaptchaAndMissingCredentials() {
        RuntimeProperties runtime = new RuntimeProperties("api", true, false);
        SecurityProperties security = new SecurityProperties(
                "x".repeat(48), true, "Strict", List.of("https://app.example.com"));

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(), new AiRuntimeProperties("", false),
                materialParser(), disabledHcaptcha(), contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mianba.hcaptcha.enabled")
                .hasMessageContaining("mianba.hcaptcha.site-key")
                .hasMessageContaining("mianba.hcaptcha.secret");
    }

    @Test
    void productionApiRejectsNonOfficialHcaptchaEndpoint() {
        RuntimeProperties runtime = new RuntimeProperties("api", true, false);
        SecurityProperties security = new SecurityProperties(
                "x".repeat(48), true, "Strict", List.of("https://app.example.com"));
        HcaptchaProperties customEndpoint = new HcaptchaProperties(
                true, "site-key", "secret", URI.create("https://captcha.example.com/siteverify"),
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16_384);

        assertThatThrownBy(() -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure(), identity(), speech(), new AiRuntimeProperties("", false),
                materialParser(), customEndpoint, contentSafety()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mianba.hcaptcha.verify-url");
    }

    private static InfrastructureProperties infrastructure() {
        return new InfrastructureProperties(
                "jdbc:postgresql://db/mianba", "user", "database-password",
                "redis", "rabbit", "rabbit-user", "rabbit-password");
    }

    private static IdentityProperties identity() {
        return new IdentityProperties(120, 300, false, "from@example.com", "resend-key");
    }

    private static SpeechProperties speech() {
        return new SpeechProperties(
                "app-id", "secret-id", "secret-key", "16k_zh_en", "16k_en",
                List.of(603006), 1, 300, 2);
    }

    private static MaterialParserProperties materialParser() {
        return new MaterialParserProperties(
                URI.create("http://material-parser:8090"), "p".repeat(32),
                Duration.ofSeconds(1), Duration.ofSeconds(8), 5 * 1024 * 1024, 65_536);
    }

    private static HcaptchaProperties hcaptcha() {
        return new HcaptchaProperties(
                true, "site-key", "secret", HcaptchaProperties.OFFICIAL_VERIFY_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16_384);
    }

    private static HcaptchaProperties disabledHcaptcha() {
        return new HcaptchaProperties(
                false, "", "", HcaptchaProperties.OFFICIAL_VERIFY_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16_384);
    }

    private static ContentSafetyProperties contentSafety() {
        return new ContentSafetyProperties("h".repeat(48));
    }
}
