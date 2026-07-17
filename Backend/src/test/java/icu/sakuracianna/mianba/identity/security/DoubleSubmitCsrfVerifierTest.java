package icu.sakuracianna.mianba.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DoubleSubmitCsrfVerifierTest {

    @Test
    void acceptsOnlyConstantTimeEqualCookieAndHeaderValues() {
        DoubleSubmitCsrfVerifier verifier = new DoubleSubmitCsrfVerifier();

        assertThat(verifier.matches("csrf-token", "csrf-token")).isTrue();
        assertThat(verifier.matches("csrf-token", "other-token")).isFalse();
        assertThat(verifier.matches(null, "csrf-token")).isFalse();
    }
}
