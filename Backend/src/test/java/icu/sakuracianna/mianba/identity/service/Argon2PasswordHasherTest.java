package icu.sakuracianna.mianba.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class Argon2PasswordHasherTest {

    @Test
    void storesPasswordsUsingArgon2idAndVerifiesThem() {
        Argon2PasswordHasher hasher = new Argon2PasswordHasher();

        String encoded = hasher.hash("correct-horse-battery-staple");

        assertThat(encoded).startsWith("$argon2id$");
        assertThat(hasher.matches("correct-horse-battery-staple", encoded)).isTrue();
        assertThat(hasher.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    void rejectsBeforeEnteringEncoderWhenGlobalCapacityIsFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PasswordEncoder blocking = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "encoded";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return true;
            }
        };
        Argon2PasswordHasher hasher = new Argon2PasswordHasher(
                blocking, 1, Duration.ofMillis(10));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> hasher.hash("first-password"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> hasher.hash("second-password"))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.detail()).isEqualTo("auth_capacity_full"));

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("encoded");
        } finally {
            release.countDown();
        }
    }
}
