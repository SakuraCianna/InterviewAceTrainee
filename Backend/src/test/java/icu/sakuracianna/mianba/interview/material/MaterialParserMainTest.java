package icu.sakuracianna.mianba.interview.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MaterialParserMainTest {

    @Test
    void parseDeadlineDefaultsToEightSecondsAndAcceptsOnlyOneThroughThirtySeconds() {
        assertThat(MaterialParserServer.DEFAULT_PARSE_DEADLINE).isEqualTo(Duration.ofSeconds(8));
        assertThat(MaterialParserMain.parseTimeoutSeconds("1")).isEqualTo(1);
        assertThat(MaterialParserMain.parseTimeoutSeconds("8")).isEqualTo(8);
        assertThat(MaterialParserMain.parseTimeoutSeconds("30")).isEqualTo(30);
        assertThatThrownBy(() -> MaterialParserMain.parseTimeoutSeconds("0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaterialParserMain.parseTimeoutSeconds("31"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
