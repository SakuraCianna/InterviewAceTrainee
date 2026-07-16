package icu.sakuracianna.mianba.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentitySettingsTest {
    @Test
    void parsesCompleteJsonScalarSnapshot() {
        IdentitySettings settings = IdentitySettings.fromJsonScalars(Map.of(
                IdentitySettings.REGISTRATION_OPEN, "true",
                IdentitySettings.PASSWORD_LOGIN_ENABLED, "false",
                IdentitySettings.EMAIL_CODE_LOGIN_ENABLED, "true",
                IdentitySettings.NEW_USER_DEFAULT_CREDITS, "8",
                IdentitySettings.NEW_USER_TRIAL_VOUCHERS, "2"));

        assertThat(settings).isEqualTo(new IdentitySettings(true, false, true, 8, 2));
    }

    @Test
    void rejectsMissingOrMalformedPersistentConfiguration() {
        assertThatThrownBy(() -> IdentitySettings.fromJsonScalars(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing system config");
        assertThatThrownBy(() -> IdentitySettings.normalizeConfigValue(
                IdentitySettings.REGISTRATION_OPEN, "true"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsWholeJsonNumbersButRejectsFractionsAndOutOfRangeValues() {
        assertThat(IdentitySettings.normalizeConfigValue(
                IdentitySettings.NEW_USER_DEFAULT_CREDITS, 12.0)).isEqualTo(12);
        assertThatThrownBy(() -> IdentitySettings.normalizeConfigValue(
                IdentitySettings.NEW_USER_DEFAULT_CREDITS, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentitySettings.normalizeConfigValue(
                IdentitySettings.NEW_USER_TRIAL_VOUCHERS, 21))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
