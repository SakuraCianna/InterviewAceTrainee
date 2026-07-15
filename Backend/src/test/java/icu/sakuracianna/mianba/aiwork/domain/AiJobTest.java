package icu.sakuracianna.mianba.aiwork.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiJobTest {

    @Test
    void retryableFailureCanRetryAtMostThreeAttempts() {
        Instant now = Instant.parse("2026-07-14T12:00:00Z");
        AiJob job = AiJob.queued(UUID.randomUUID(), UUID.randomUUID(), JobKind.GENERATE_FOLLOW_UP,
                "user-resource-body-hash", now, now.plus(Duration.ofMinutes(10)));

        for (int attempt = 1; attempt <= 3; attempt++) {
            job.claim("worker-a", now, Duration.ofSeconds(30), job.version());
            job.fail(new JobFailure("AI_PROVIDER_TIMEOUT", true), now, job.version());
            if (attempt < 3) {
                assertThat(job.status()).isEqualTo(JobStatus.RETRYING);
                job.requeue(now, job.version());
            }
        }

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.retryable()).isFalse();
        assertThat(job.attempt()).isEqualTo(3);
    }

    @Test
    void staleOrExpiredJobCannotBeClaimed() {
        Instant now = Instant.parse("2026-07-14T12:00:00Z");
        AiJob stale = AiJob.queued(UUID.randomUUID(), UUID.randomUUID(), JobKind.GENERATE_REPORT,
                "key", now.minusSeconds(120), now.minusSeconds(1));

        assertThatThrownBy(() -> stale.claim("worker", now, Duration.ofSeconds(30), stale.version()))
                .isInstanceOf(StaleJobException.class);
        assertThat(stale.status()).isEqualTo(JobStatus.CANCELLED);
    }
}
