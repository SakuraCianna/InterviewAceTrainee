package icu.sakuracianna.mianba.interview.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MaterialParserPropertiesTest {

    @Test
    void clampsParserResponseBytesAtSixtyFourKibibytesBoundary() {
        assertThat(properties(65_535).maxResponseBytes()).isEqualTo(65_535);
        assertThat(properties(65_536).maxResponseBytes()).isEqualTo(65_536);
        assertThat(properties(65_537).maxResponseBytes()).isEqualTo(65_536);
    }

    private static MaterialParserProperties properties(int maxResponseBytes) {
        return new MaterialParserProperties(
                URI.create("http://127.0.0.1:8090"), "", Duration.ofSeconds(1),
                Duration.ofSeconds(8), MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES, maxResponseBytes);
    }
}
