package icu.sakuracianna.mianba.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorEnvelopeTest {

    @Test
    void stableErrorProtocolAlwaysCarriesAllFourFields() {
        ErrorEnvelope response = ErrorEnvelope.of("invalid_request", "请求参数不正确", "req_123", List.of());

        assertThat(response.detail()).isEqualTo("invalid_request");
        assertThat(response.message()).isEqualTo("请求参数不正确");
        assertThat(response.requestId()).isEqualTo("req_123");
        assertThat(response.errors()).isEmpty();
    }
}
