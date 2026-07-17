package icu.sakuracianna.mianba.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditAccountTest {

    @Test
    void debitIsIdempotentAndNeverMakesBalanceNegative() {
        CreditAccount account = new CreditAccount(UUID.randomUUID(), 2, 0);

        CreditMutation first = account.debit("start-session-1", 2, "interview:job");
        CreditMutation duplicate = account.debit("start-session-1", 2, "interview:job");

        assertThat(first).isEqualTo(duplicate);
        assertThat(account.balance()).isZero();
        assertThat(account.mutations()).hasSize(1);
        assertThatThrownBy(() -> account.debit("start-session-2", 1, "interview:civil_service"))
                .isInstanceOf(InsufficientCreditException.class);
    }

    @Test
    void staleOptimisticVersionIsRejected() {
        CreditAccount account = new CreditAccount(UUID.randomUUID(), 3, 7);

        assertThatThrownBy(() -> account.debit("operation", 1, "interview:job", 6))
                .isInstanceOf(StaleCreditVersionException.class);
    }
}
