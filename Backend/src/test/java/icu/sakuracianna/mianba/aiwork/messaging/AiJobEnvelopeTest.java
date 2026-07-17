package icu.sakuracianna.mianba.aiwork.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class AiJobEnvelopeTest {

    @Test
    void roundTripsWithExplicitSnakeCaseProtocol() throws Exception {
        JsonMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        AiJobEnvelope expected = AiJobEnvelope.create(
                UUID.randomUUID(), "req-123", "trace-123", Instant.parse("2026-07-14T08:00:00Z"));

        String payload = mapper.writeValueAsString(expected);
        AiJobEnvelope actual = mapper.readValue(payload, AiJobEnvelope.class);

        assertThat(payload).contains("\"schema_version\"")
                .contains("\"message_id\"")
                .contains("\"job_id\"")
                .doesNotContain("schemaVersion")
                .doesNotContain("messageId");
        assertThat(actual).isEqualTo(expected);
    }
}
