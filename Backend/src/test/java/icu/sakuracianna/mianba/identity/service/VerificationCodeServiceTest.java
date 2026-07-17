package icu.sakuracianna.mianba.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.platform.config.IdentityProperties;
import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class VerificationCodeServiceTest {
    @Test
    void deliveryFailureConditionallyRevokesIssuedCode() {
        OneTimeCodeStore store = mock(OneTimeCodeStore.class);
        VerificationCodeDelivery delivery = mock(VerificationCodeDelivery.class);
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(123456);
        org.mockito.Mockito.doThrow(new IllegalStateException("mail unavailable"))
                .when(delivery).deliver("user@example.com", "123456", 300);
        VerificationCodeService service = new VerificationCodeService(
                store, delivery, new IdentityProperties(120, 300, false, "noreply@example.com"), random);

        assertThatThrownBy(() -> service.issue(" USER@example.com "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mail unavailable");

        verify(store).issue("user@example.com", "123456", Duration.ofSeconds(300));
        verify(store).revoke("user@example.com", "123456");
    }
}
