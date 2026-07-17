package icu.sakuracianna.mianba.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeechPropertiesTest {

    @Test
    void fallsBackToDefaultVoiceWhenConfiguredValuesAreAllInvalid() {
        SpeechProperties properties = new SpeechProperties(
                "app-id", "secret-id", "secret-key", "16k_zh_en", "16k_en",
                List.of(-1, 0), 1, 300, 2);

        assertThat(properties.ttsVoiceTypes()).containsExactly(603006);
        assertThat(properties.voiceType(UUID.randomUUID())).isEqualTo(603006);
    }
}
