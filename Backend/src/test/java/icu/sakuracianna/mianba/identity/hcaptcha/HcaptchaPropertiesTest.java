package icu.sakuracianna.mianba.identity.hcaptcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HcaptchaPropertiesTest {

    @Test
    void appliesSafeNetworkDefaultsAndHardResponseLimit() {
        HcaptchaProperties properties = new HcaptchaProperties(
                false, null, null, null, Duration.ZERO, Duration.ofSeconds(-1), 100_000);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.siteKey()).isEmpty();
        assertThat(properties.secret()).isEmpty();
        assertThat(properties.verifyUrl()).isEqualTo(HcaptchaProperties.OFFICIAL_VERIFY_URL);
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.maxResponseBytes()).isEqualTo(HcaptchaProperties.HARD_MAX_RESPONSE_BYTES);
    }

    @Test
    void rejectsCredentialControlCharactersAndNonHttpEndpoint() {
        assertThatThrownBy(() -> new HcaptchaProperties(
                true, "site-key\n", "secret", HcaptchaProperties.OFFICIAL_VERIFY_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16_384))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("site-key");
        assertThatThrownBy(() -> new HcaptchaProperties(
                true, "site-key", "secret", URI.create("file:///tmp/siteverify"),
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16_384))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verify-url");
    }

    @Test
    void diagnosticStringNeverContainsCredentials() {
        HcaptchaProperties properties = new HcaptchaProperties(
                true, "private-site-value", "private-server-value",
                HcaptchaProperties.OFFICIAL_VERIFY_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16_384);

        assertThat(properties.toString())
                .contains("siteKeyConfigured=true", "secretConfigured=true")
                .doesNotContain("private-site-value", "private-server-value");
    }
}
