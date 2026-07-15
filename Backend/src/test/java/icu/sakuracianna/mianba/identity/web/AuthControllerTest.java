package icu.sakuracianna.mianba.identity.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import icu.sakuracianna.mianba.identity.hcaptcha.HcaptchaProperties;
import icu.sakuracianna.mianba.identity.hcaptcha.HumanVerification;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.identity.security.JwtService;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.identity.service.AuthAttemptRecorder;
import icu.sakuracianna.mianba.identity.service.AuthService;
import icu.sakuracianna.mianba.identity.service.LoginSession;
import icu.sakuracianna.mianba.identity.service.UserAccount;
import icu.sakuracianna.mianba.identity.service.VerificationCodeService;
import icu.sakuracianna.mianba.platform.config.AbuseProtectionProperties;
import icu.sakuracianna.mianba.platform.config.IdentityProperties;
import icu.sakuracianna.mianba.platform.config.SecurityProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    @Test
    void passwordLoginReturnsUserSummaryAndKeepsBearerInsideSecureCookie() throws Exception {
        AuthService auth = mock(AuthService.class);
        JwtService jwt = mock(JwtService.class);
        VerificationCodeService codes = mock(VerificationCodeService.class);
        AbuseProtection abuseProtection = mock(AbuseProtection.class);
        AuthAttemptRecorder attemptRecorder = mock(AuthAttemptRecorder.class);
        HumanVerification humanVerification = mock(HumanVerification.class);
        UserAccount user = new UserAccount(UUID.randomUUID(), "user@example.com", "hash", "user", 3, true, 0);
        LoginSession session = new LoginSession(user, UUID.randomUUID());
        when(auth.passwordLogin("user@example.com", "secret123")).thenReturn(session);
        when(jwt.issue(any(), any(), any(), any(), anyLong(), any())).thenReturn("signed.jwt.token");
        AuthController controller = new AuthController(
                auth,
                jwt,
                codes,
                abuseProtection,
                attemptRecorder,
                humanVerification,
                new IdentityProperties(120, 300, false, ""),
                hcaptcha(),
                new SecurityProperties("x".repeat(40), true, "Strict", List.of("https://app.example.com")),
                new AbuseProtectionProperties(20, 8, 5, 5, 3, 600));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/auth/password/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"secret123",
                                 "captcha_token":"captcha-response"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.credit_balance").value(3))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(cookie().value(AuthController.ACCESS_TOKEN_COOKIE, "signed.jwt.token"))
                .andExpect(cookie().httpOnly(AuthController.ACCESS_TOKEN_COOKIE, true))
                .andExpect(cookie().httpOnly(AuthController.CSRF_TOKEN_COOKIE, false))
                .andExpect(cookie().secure(AuthController.ACCESS_TOKEN_COOKIE, true));

        InOrder order = inOrder(abuseProtection, humanVerification, auth);
        order.verify(abuseProtection).check(eq("password-ip"), eq("203.0.113.10"), eq(20), any());
        order.verify(abuseProtection).check(eq("password-email"), eq("user@example.com"), eq(8), any());
        order.verify(humanVerification).verify("captcha-response", "203.0.113.10");
        order.verify(auth).passwordLogin("user@example.com", "secret123");
    }

    @Test
    void passwordResetIsRateLimitedAndAuditedWithoutCredentialData() throws Exception {
        AuthService auth = mock(AuthService.class);
        JwtService jwt = mock(JwtService.class);
        VerificationCodeService codes = mock(VerificationCodeService.class);
        AbuseProtection abuseProtection = mock(AbuseProtection.class);
        AuthAttemptRecorder attemptRecorder = mock(AuthAttemptRecorder.class);
        HumanVerification humanVerification = mock(HumanVerification.class);
        AuthController controller = new AuthController(
                auth,
                jwt,
                codes,
                abuseProtection,
                attemptRecorder,
                humanVerification,
                new IdentityProperties(120, 300, false, ""),
                hcaptcha(),
                new SecurityProperties("x".repeat(40), true, "Strict", List.of("https://app.example.com")),
                new AbuseProtectionProperties(20, 8, 5, 5, 3, 600));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/auth/password/reset")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"User@Example.com","code":"123456","new_password":"new-secret-123"}
                                """))
                .andExpect(status().isNoContent());

        verify(abuseProtection).check(eq("password_reset-ip"), eq("203.0.113.10"), eq(20), any());
        verify(abuseProtection).check(eq("password_reset-email"), eq("user@example.com"), eq(8), any());
        verify(auth).resetPassword("User@Example.com", "123456", "new-secret-123");
        verify(attemptRecorder).record(
                eq(null), eq("user@example.com"), eq("password_reset"), eq("user"), eq(true), eq(null),
                eq("203.0.113.10"), eq(null), eq("req_unknown"));
        verify(humanVerification, never()).verify(any(), any());
    }

    @Test
    void adminLoginRunsCaptchaAfterRateLimitsAndBeforeCredentialCheck() throws Exception {
        AuthService auth = mock(AuthService.class);
        JwtService jwt = mock(JwtService.class);
        VerificationCodeService codes = mock(VerificationCodeService.class);
        AbuseProtection abuseProtection = mock(AbuseProtection.class);
        AuthAttemptRecorder attemptRecorder = mock(AuthAttemptRecorder.class);
        HumanVerification humanVerification = mock(HumanVerification.class);
        UserAccount admin = new UserAccount(
                UUID.randomUUID(), "admin@example.com", "hash", "admin", 0, true, 0);
        when(auth.adminLogin("admin@example.com", "secret123", "123456"))
                .thenReturn(new LoginSession(admin, UUID.randomUUID()));
        when(jwt.issue(any(), any(), any(), any(), anyLong(), any())).thenReturn("signed.jwt.token");
        AuthController controller = controller(
                auth, jwt, codes, abuseProtection, attemptRecorder, humanVerification);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/auth/admin/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.11");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.com","password":"secret123","code":"123456",
                                 "captcha_token":"admin-captcha"}
                                """))
                .andExpect(status().isOk());

        InOrder order = inOrder(abuseProtection, humanVerification, auth);
        order.verify(abuseProtection).check(eq("admin_password_code-ip"),
                eq("203.0.113.11"), eq(5), any());
        order.verify(abuseProtection).check(eq("admin_password_code-email"),
                eq("admin@example.com"), eq(5), any());
        order.verify(humanVerification).verify("admin-captcha", "203.0.113.11");
        order.verify(auth).adminLogin("admin@example.com", "secret123", "123456");
    }

    @Test
    void emailCodeRequestSkipsOnlyTheAuthenticatedUsersOwnEmail() {
        AuthService auth = mock(AuthService.class);
        JwtService jwt = mock(JwtService.class);
        VerificationCodeService codes = mock(VerificationCodeService.class);
        AbuseProtection abuseProtection = mock(AbuseProtection.class);
        AuthAttemptRecorder attemptRecorder = mock(AuthAttemptRecorder.class);
        HumanVerification humanVerification = mock(HumanVerification.class);
        when(codes.issue("user@example.com"))
                .thenReturn(new VerificationCodeService.IssuedCode("user@example.com", 300, ""));
        when(codes.issue("other@example.com"))
                .thenReturn(new VerificationCodeService.IssuedCode("other@example.com", 300, ""));
        AuthController controller = controller(
                auth, jwt, codes, abuseProtection, attemptRecorder, humanVerification);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.12");

        controller.requestEmailCode(
                new AuthController.EmailCodeRequest("user@example.com", "email-captcha"), request, null);

        InOrder order = inOrder(abuseProtection, humanVerification, codes);
        order.verify(abuseProtection).check(eq("email-code-ip"), eq("203.0.113.12"), eq(5), any());
        order.verify(abuseProtection).check(eq("email-code-email"), eq("user@example.com"), eq(3), any());
        order.verify(humanVerification).verify("email-captcha", "203.0.113.12");
        order.verify(codes).issue("user@example.com");

        AuthenticatedUser principal = new AuthenticatedUser(
                UUID.randomUUID(), "user@example.com", "user", UUID.randomUUID(), 0);
        controller.requestEmailCode(
                new AuthController.EmailCodeRequest("user@example.com", null), request, principal);

        controller.requestEmailCode(
                new AuthController.EmailCodeRequest("other@example.com", "cross-account-captcha"),
                request,
                principal);

        verify(humanVerification).verify("email-captcha", "203.0.113.12");
        verify(humanVerification).verify("cross-account-captcha", "203.0.113.12");
    }

    @Test
    void hcaptchaConfigExposesOnlyPublicBrowserFields() throws Exception {
        AuthController controller = controller(
                mock(AuthService.class),
                mock(JwtService.class),
                mock(VerificationCodeService.class),
                mock(AbuseProtection.class),
                mock(AuthAttemptRecorder.class),
                mock(HumanVerification.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/auth/hcaptcha/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.site_key").value("site-key"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.verify_url").doesNotExist());
    }

    private static AuthController controller(
            AuthService auth,
            JwtService jwt,
            VerificationCodeService codes,
            AbuseProtection abuseProtection,
            AuthAttemptRecorder attemptRecorder,
            HumanVerification humanVerification) {
        return new AuthController(
                auth,
                jwt,
                codes,
                abuseProtection,
                attemptRecorder,
                humanVerification,
                new IdentityProperties(120, 300, false, ""),
                hcaptcha(),
                new SecurityProperties("x".repeat(40), true, "Strict", List.of("https://app.example.com")),
                new AbuseProtectionProperties(20, 8, 5, 5, 3, 600));
    }

    private static HcaptchaProperties hcaptcha() {
        return new HcaptchaProperties(
                true, "site-key", "server-secret", URI.create("https://api.hcaptcha.com/siteverify"),
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16_384);
    }
}
