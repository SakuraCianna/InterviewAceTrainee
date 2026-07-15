package icu.sakuracianna.mianba.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    private UserAccountRepository users;
    private OneTimeCodeStore codes;
    private PasswordHasher passwords;
    private SessionRegistry sessions;
    private TrialVoucherIssuer vouchers;
    private IdentitySettingsProvider settings;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        codes = mock(OneTimeCodeStore.class);
        passwords = mock(PasswordHasher.class);
        sessions = mock(SessionRegistry.class);
        vouchers = mock(TrialVoucherIssuer.class);
        settings = mock(IdentitySettingsProvider.class);
        when(settings.current()).thenReturn(new IdentitySettings(true, true, true, 0, 1));
        service = new AuthService(
                users, codes, passwords, sessions, vouchers, settings, Duration.ofHours(2));
    }

    @Test
    void registerConsumesCodeNormalizesEmailAndIssuesTrialVoucher() {
        UUID userId = UUID.randomUUID();
        UserAccount created = new UserAccount(userId, "new@example.com", "hash", "user", 0, true, 0);
        when(codes.consume("new@example.com", "123456")).thenReturn(true);
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(passwords.hash("secret123")).thenReturn("hash");
        when(users.create("new@example.com", "hash", 0)).thenReturn(created);

        LoginSession result = service.register(" NEW@Example.com ", "secret123", "123456");

        assertThat(result.user()).isEqualTo(created);
        verify(vouchers).issueRegistrationVouchers(userId, 1);
        verify(sessions).create(userId, result.sessionId(), Duration.ofHours(2));
    }

    @Test
    void registerUsesConfiguredCreditsAndVoucherQuantity() {
        UUID userId = UUID.randomUUID();
        UserAccount created = new UserAccount(userId, "new@example.com", "hash", "user", 7, true, 0);
        when(settings.current()).thenReturn(new IdentitySettings(true, true, true, 7, 2));
        when(codes.consume("new@example.com", "123456")).thenReturn(true);
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(passwords.hash("secret123")).thenReturn("hash");
        when(users.create("new@example.com", "hash", 7)).thenReturn(created);

        service.register("new@example.com", "secret123", "123456");

        verify(users).create("new@example.com", "hash", 7);
        verify(vouchers).issueRegistrationVouchers(userId, 2);
    }

    @Test
    void closedRegistrationRejectsBeforeConsumingCode() {
        when(settings.current()).thenReturn(new IdentitySettings(false, true, true, 0, 1));

        assertThatThrownBy(() -> service.register("new@example.com", "secret123", "123456"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("registration_closed"));

        verify(codes, never()).consume("new@example.com", "123456");
    }

    @Test
    void disabledPasswordLoginRejectsBeforeAccountLookup() {
        when(settings.current()).thenReturn(new IdentitySettings(true, false, true, 0, 1));

        assertThatThrownBy(() -> service.passwordLogin("user@example.com", "secret123"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("password_login_disabled"));

        verify(users, never()).findByEmail("user@example.com");
    }

    @Test
    void ordinaryPasswordLoginRejectsAdminAccount() {
        UserAccount admin = new UserAccount(UUID.randomUUID(), "admin@example.com", "hash", "admin", 0, true, 0);
        when(users.findByEmailForUpdate("admin@example.com")).thenReturn(Optional.of(admin));
        when(passwords.matches("secret123", "hash")).thenReturn(true);

        assertThatThrownBy(() -> service.passwordLogin("admin@example.com", "secret123"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("admin_login_required"));
    }

    @Test
    void unknownPasswordLoginRunsFixedArgon2VerificationAndMatchesKnownFailure() {
        UserAccount existing = new UserAccount(
                UUID.randomUUID(), "known@example.com", "known-hash", "user", 0, true, 0);
        when(users.findByEmailForUpdate("known@example.com")).thenReturn(Optional.of(existing));
        when(users.findByEmailForUpdate("unknown@example.com")).thenReturn(Optional.empty());
        when(passwords.matches("wrong-password", "known-hash")).thenReturn(false);

        ApiException knownFailure = catchThrowableOfType(
                ApiException.class,
                () -> service.passwordLogin("known@example.com", "wrong-password"));
        ApiException unknownFailure = catchThrowableOfType(
                ApiException.class,
                () -> service.passwordLogin("unknown@example.com", "wrong-password"));

        assertThat(unknownFailure.status()).isEqualTo(knownFailure.status());
        assertThat(unknownFailure.detail()).isEqualTo(knownFailure.detail());
        assertThat(unknownFailure.getMessage()).isEqualTo(knownFailure.getMessage());
        verify(passwords).matches("wrong-password", "known-hash");
        verify(passwords).matches(eq("wrong-password"), argThat(hash -> hash.startsWith("$argon2id$")));
        verify(passwords, never()).hash(anyString());
    }

    @Test
    void passwordTimingHashIsAValidFixedArgon2Encoding() {
        Argon2PasswordHasher realHasher = new Argon2PasswordHasher();

        assertThat(realHasher.matches("not-the-placeholder-password", AuthService.PASSWORD_TIMING_HASH))
                .isFalse();
    }

    @Test
    void adminLoginRequiresBothPasswordAndSingleUseCode() {
        UserAccount admin = new UserAccount(UUID.randomUUID(), "admin@example.com", "hash", "admin", 0, true, 0);
        when(users.findByEmailForUpdate("admin@example.com")).thenReturn(Optional.of(admin));
        when(passwords.matches("secret123", "hash")).thenReturn(true);
        when(codes.consume("admin@example.com", "654321")).thenReturn(false);

        assertThatThrownBy(() -> service.adminLogin("admin@example.com", "secret123", "654321"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("invalid_email_code"));
    }

    @Test
    void closedRegistrationReturnsSameInvalidCodeFailureForKnownAndUnknownEmails() {
        when(settings.current()).thenReturn(new IdentitySettings(false, true, true, 0, 1));
        when(codes.consume("known@example.com", "000000")).thenReturn(false);
        when(codes.consume("unknown@example.com", "000000")).thenReturn(false);

        ApiException knownFailure = catchThrowableOfType(
                ApiException.class,
                () -> service.emailCodeLogin("known@example.com", "000000"));
        ApiException unknownFailure = catchThrowableOfType(
                ApiException.class,
                () -> service.emailCodeLogin("unknown@example.com", "000000"));

        assertThat(unknownFailure.status()).isEqualTo(knownFailure.status());
        assertThat(unknownFailure.detail()).isEqualTo(knownFailure.detail());
        assertThat(unknownFailure.getMessage()).isEqualTo(knownFailure.getMessage());
        assertThat(unknownFailure.detail()).isEqualTo("invalid_email_code");
        verify(codes).consume("known@example.com", "000000");
        verify(codes).consume("unknown@example.com", "000000");
        verify(users, never()).findByEmail("known@example.com");
        verify(users, never()).findByEmail("unknown@example.com");
        verify(users, never()).findByEmailForUpdate("known@example.com");
        verify(users, never()).findByEmailForUpdate("unknown@example.com");
    }

    @Test
    void validCodeForUnknownEmailReportsClosedRegistrationOnlyAfterCodeVerification() {
        when(settings.current()).thenReturn(new IdentitySettings(false, true, true, 0, 1));
        when(codes.consume("unknown@example.com", "123456")).thenReturn(true);
        when(users.findByEmailForUpdate("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.emailCodeLogin("unknown@example.com", "123456"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(error.detail()).isEqualTo("registration_closed");
                });

        verify(codes).consume("unknown@example.com", "123456");
        verify(users, never()).findByEmail("unknown@example.com");
        verify(users, never()).create(eq("unknown@example.com"), isNull(), eq(0));
    }

    @Test
    void closedRegistrationStillAllowsKnownUserWithValidEmailCode() {
        UserAccount existing = new UserAccount(
                UUID.randomUUID(), "known@example.com", null, "user", 2, true, 0);
        when(settings.current()).thenReturn(new IdentitySettings(false, true, true, 0, 1));
        when(codes.consume("known@example.com", "123456")).thenReturn(true);
        when(users.findByEmailForUpdate("known@example.com")).thenReturn(Optional.of(existing));

        LoginSession result = service.emailCodeLogin("known@example.com", "123456");

        assertThat(result.user()).isEqualTo(existing);
        verify(sessions).create(existing.id(), result.sessionId(), Duration.ofHours(2));
        verify(users, never()).create(eq("known@example.com"), isNull(), eq(0));
    }

    @Test
    void passwordResetDoesNotRevealWhetherEmailIsRegisteredWhenCodeIsInvalid() {
        UserAccount existing = new UserAccount(
                UUID.randomUUID(), "known@example.com", "hash", "user", 0, true, 0);
        when(users.findByEmailForUpdate("known@example.com")).thenReturn(Optional.of(existing));
        when(users.findByEmailForUpdate("unknown@example.com")).thenReturn(Optional.empty());
        when(codes.consume("known@example.com", "000000")).thenReturn(false);
        when(codes.consume("unknown@example.com", "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.resetPassword("known@example.com", "000000", "new-secret-123"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("invalid_email_code"));
        assertThatThrownBy(() -> service.resetPassword("unknown@example.com", "000000", "new-secret-123"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("invalid_email_code"));

        verify(users, never()).findByEmailForUpdate("known@example.com");
        verify(users, never()).findByEmailForUpdate("unknown@example.com");
        verify(passwords, never()).hash("new-secret-123");
    }
}
