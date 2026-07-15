package icu.sakuracianna.mianba.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class ValidIdempotencyKeyTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsStablePrintableKeys() {
        assertThat(validator.validate(new Request("answer:550e8400-e29b-41d4-a716-446655440000")))
                .isEmpty();
    }

    @Test
    void rejectsWhitespaceControlsAndExcessiveLength() {
        assertThat(validator.validate(new Request("contains space"))).isNotEmpty();
        assertThat(validator.validate(new Request("contains\u0000nul"))).isNotEmpty();
        assertThat(validator.validate(new Request("a".repeat(129)))).isNotEmpty();
    }

    private record Request(@ValidIdempotencyKey String key) {
    }
}
