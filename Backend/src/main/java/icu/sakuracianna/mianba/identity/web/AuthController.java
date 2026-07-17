package icu.sakuracianna.mianba.identity.web;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import icu.sakuracianna.mianba.platform.web.ApiException;
import icu.sakuracianna.mianba.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 面向浏览器的身份入口，负责限流、审计以及安全 Cookie 的协议适配。 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class AuthController {
    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String CSRF_TOKEN_COOKIE = "csrf_token";

    private final AuthService auth;
    private final JwtService jwt;
    private final VerificationCodeService codes;
    private final AbuseProtection abuseProtection;
    private final AuthAttemptRecorder attemptRecorder;
    private final HumanVerification humanVerification;
    private final IdentityProperties identityProperties;
    private final HcaptchaProperties hcaptchaProperties;
    private final SecurityProperties securityProperties;
    private final AbuseProtectionProperties abuseProperties;

    public AuthController(
            AuthService auth,
            JwtService jwt,
            VerificationCodeService codes,
            AbuseProtection abuseProtection,
            AuthAttemptRecorder attemptRecorder,
            HumanVerification humanVerification,
            IdentityProperties identityProperties,
            HcaptchaProperties hcaptchaProperties,
            SecurityProperties securityProperties,
            AbuseProtectionProperties abuseProperties) {
        this.auth = auth;
        this.jwt = jwt;
        this.codes = codes;
        this.abuseProtection = abuseProtection;
        this.attemptRecorder = attemptRecorder;
        this.humanVerification = humanVerification;
        this.identityProperties = identityProperties;
        this.hcaptchaProperties = hcaptchaProperties;
        this.securityProperties = securityProperties;
        this.abuseProperties = abuseProperties;
    }

    /** 使用密码和邮箱验证码注册普通用户。 */
    @PostMapping("/password/register")
    ResponseEntity<CurrentUserResponse> register(
            @Valid @RequestBody PasswordRegisterRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        LoginSession session = authenticate(
                request.email(), "register", "user", servletRequest,
                abuseProperties.loginIpLimit(), abuseProperties.loginEmailLimit(),
                () -> auth.register(request.email(), request.password(), request.code()));
        return loginResponse(session, response, 201);
    }

    /** 使用密码登录普通用户并设置访问令牌与 CSRF Cookie。 */
    @PostMapping("/password/login")
    ResponseEntity<CurrentUserResponse> passwordLogin(
            @Valid @RequestBody PasswordLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        LoginSession session = authenticate(
                request.email(), "password", "user", servletRequest,
                abuseProperties.loginIpLimit(), abuseProperties.loginEmailLimit(),
                () -> {
                    humanVerification.verify(request.captchaToken(), clientIp(servletRequest));
                    return auth.passwordLogin(request.email(), request.password());
                });
        return loginResponse(session, response, 200);
    }

    /** 使用邮箱验证码登录；符合配置时可自动注册普通用户。 */
    @PostMapping("/email-code/login")
    ResponseEntity<CurrentUserResponse> emailCodeLogin(
            @Valid @RequestBody EmailCodeLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        LoginSession session = authenticate(
                request.email(), "email_code", "user", servletRequest,
                abuseProperties.loginIpLimit(), abuseProperties.loginEmailLimit(),
                () -> auth.emailCodeLogin(request.email(), request.code()));
        return loginResponse(session, response, 200);
    }

    /** 使用密码和邮箱验证码完成管理员双因素登录。 */
    @PostMapping("/admin/login")
    ResponseEntity<CurrentUserResponse> adminLogin(
            @Valid @RequestBody AdminLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        LoginSession session = authenticate(
                request.email(), "admin_password_code", "admin", servletRequest,
                abuseProperties.adminLoginLimit(), abuseProperties.adminLoginLimit(),
                () -> {
                    humanVerification.verify(request.captchaToken(), clientIp(servletRequest));
                    return auth.adminLogin(request.email(), request.password(), request.code());
                });
        return loginResponse(session, response, 200);
    }

    /** 经过 IP 与邮箱双维度限流后签发邮箱验证码；仅当前账号邮箱免重复挑战。 */
    @PostMapping("/email-code/request")
    ResponseEntity<EmailCodeResponse> requestEmailCode(
            @Valid @RequestBody EmailCodeRequest request,
            HttpServletRequest servletRequest,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Duration window = Duration.ofSeconds(abuseProperties.windowSeconds());
        String ipAddress = clientIp(servletRequest);
        String email = normalizeEmail(request.email());
        abuseProtection.check("email-code-ip", ipAddress,
                abuseProperties.emailCodeIpLimit(), window);
        abuseProtection.check("email-code-email", email,
                abuseProperties.emailCodeEmailLimit(), window);
        boolean ownAccountEmail = principal != null
                && email.equals(normalizeEmail(principal.email()));
        if (!ownAccountEmail) {
            humanVerification.verify(request.captchaToken(), ipAddress);
        }
        VerificationCodeService.IssuedCode issued = codes.issue(request.email());
        return ResponseEntity.accepted().body(new EmailCodeResponse(
                issued.email(), issued.expiresInSeconds(), issued.devCode()));
    }

    /** 撤销服务端会话，并令浏览器立即删除认证 Cookie。 */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletResponse response) {
        if (principal != null) {
            auth.logout(principal.userId(), principal.sessionId());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie(ACCESS_TOKEN_COOKIE, true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie(CSRF_TOKEN_COOKIE, false).toString());
        return ResponseEntity.noContent().build();
    }

    /** 使用邮箱验证码修改已登录账号密码，并撤销全部旧会话。 */
    @PostMapping("/password/change")
    ResponseEntity<Void> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        auth.changePasswordWithCode(principal.userId(), request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** 使用邮箱验证码重置密码，调用前执行敏感操作限流与审计。 */
    @PostMapping("/password/reset")
    ResponseEntity<Void> resetPassword(
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest servletRequest) {
        runSensitiveEmailAction(
                request.email(), "password_reset", "user", servletRequest,
                () -> auth.resetPassword(request.email(), request.code(), request.newPassword()));
        return ResponseEntity.noContent().build();
    }

    /** 返回当前账号的最新角色、积分和可用体验券信息。 */
    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        UserAccount user = auth.requireCurrent(principal.userId());
        return new CurrentUserResponse(
                user.email(), user.role(), user.creditBalance(), auth.availableVoucherUses(user.id()));
    }

    /** 返回浏览器渲染人机挑战所需的非敏感配置。 */
    @GetMapping("/hcaptcha/config")
    HcaptchaConfigResponse hcaptchaConfig() {
        return new HcaptchaConfigResponse(hcaptchaProperties.enabled(), hcaptchaProperties.siteKey());
    }

    private ResponseEntity<CurrentUserResponse> loginResponse(
            LoginSession session,
            HttpServletResponse response,
            int status) {
        Duration lifetime = Duration.ofMinutes(identityProperties.accessTokenMinutes());
        String token = jwt.issue(
                session.user().id(),
                session.user().email(),
                session.user().role(),
                session.sessionId(),
                session.user().authVersion(),
                lifetime);
        String csrfToken = UUID.randomUUID().toString().replace("-", "");
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(ACCESS_TOKEN_COOKIE, token, true, lifetime).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(CSRF_TOKEN_COOKIE, csrfToken, false, lifetime).toString());
        UserAccount user = session.user();
        return ResponseEntity.status(status).body(new CurrentUserResponse(
                user.email(), user.role(), user.creditBalance(), auth.availableVoucherUses(user.id())));
    }

    private LoginSession authenticate(
            String rawEmail,
            String method,
            String role,
            HttpServletRequest request,
            int ipLimit,
            int emailLimit,
            Supplier<LoginSession> operation) {
        String email = normalizeEmail(rawEmail);
        String ipAddress = clientIp(request);
        String requestId = requestId(request);
        try {
            Duration window = Duration.ofSeconds(abuseProperties.windowSeconds());
            abuseProtection.check(method + "-ip", ipAddress, ipLimit, window);
            abuseProtection.check(method + "-email", email, emailLimit, window);
            LoginSession session = operation.get();
            attemptRecorder.record(
                    session.user().id(), email, method, role, true, null,
                    ipAddress, request.getHeader(HttpHeaders.USER_AGENT), requestId);
            return session;
        } catch (RuntimeException exception) {
            String reason = exception instanceof ApiException apiException
                    ? apiException.detail()
                    : "internal_error";
            if ("rate_limit_exceeded".equals(reason) || "auth_capacity_full".equals(reason)) {
                throw exception;
            }
            attemptRecorder.record(
                    null, email, method, role, false, reason,
                    ipAddress, request.getHeader(HttpHeaders.USER_AGENT), requestId);
            throw exception;
        }
    }

    private void runSensitiveEmailAction(
            String rawEmail,
            String method,
            String role,
            HttpServletRequest request,
            Runnable operation) {
        String email = normalizeEmail(rawEmail);
        String ipAddress = clientIp(request);
        String requestId = requestId(request);
        try {
            Duration window = Duration.ofSeconds(abuseProperties.windowSeconds());
            abuseProtection.check(method + "-ip", ipAddress, abuseProperties.loginIpLimit(), window);
            abuseProtection.check(method + "-email", email, abuseProperties.loginEmailLimit(), window);
            operation.run();
            // 密码重置审计只保存事件元数据，验证码和新密码始终留在业务调用边界内。
            attemptRecorder.record(
                    null, email, method, role, true, null,
                    ipAddress, request.getHeader(HttpHeaders.USER_AGENT), requestId);
        } catch (RuntimeException exception) {
            String reason = exception instanceof ApiException apiException
                    ? apiException.detail()
                    : "internal_error";
            if ("rate_limit_exceeded".equals(reason) || "auth_capacity_full".equals(reason)) {
                throw exception;
            }
            attemptRecorder.record(
                    null, email, method, role, false, reason,
                    ipAddress, request.getHeader(HttpHeaders.USER_AGENT), requestId);
            throw exception;
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value instanceof String requestId ? requestId : "req_unknown";
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(securityProperties.cookieSecure())
                .sameSite(securityProperties.cookieSameSite())
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private ResponseCookie expiredCookie(String name, boolean httpOnly) {
        return cookie(name, "", httpOnly, Duration.ZERO);
    }

    /** 普通用户密码登录请求。 */
    public record PasswordLoginRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @JsonProperty("captcha_token") @Size(max = 4_096) String captchaToken) {
    }

    /** 普通用户密码注册请求。 */
    public record PasswordRegisterRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    /** 普通用户邮箱验证码登录请求。 */
    public record EmailCodeLoginRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    /** 管理员双因素登录请求。 */
    public record AdminLoginRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Pattern(regexp = "\\d{6}") String code,
            @JsonProperty("captcha_token") @Size(max = 4_096) String captchaToken) {
    }

    /** 邮箱验证码签发请求。 */
    public record EmailCodeRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @JsonProperty("captcha_token") @Size(max = 4_096) String captchaToken) {
    }

    /** 已登录用户改密请求。 */
    public record PasswordChangeRequest(
            @NotBlank @Pattern(regexp = "\\d{6}") String code,
            @JsonProperty("new_password") @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    /** 未登录用户找回密码请求。 */
    public record PasswordResetRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\d{6}") String code,
            @JsonProperty("new_password") @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    /** 验证码签发受理结果；生产环境的 {@code devCode} 始终为空。 */
    public record EmailCodeResponse(
            String email,
            @JsonProperty("expires_in_seconds") int expiresInSeconds,
            @JsonProperty("dev_code") String devCode) {
    }

    /** 当前用户视图，不包含密码哈希、令牌和服务端会话标识。 */
    public record CurrentUserResponse(
            String email,
            String role,
            @JsonProperty("credit_balance") int creditBalance,
            @JsonProperty("trial_voucher_count") int trialVoucherCount) {
    }

    /** 浏览器可公开读取的 hCaptcha 开关与站点公钥。 */
    public record HcaptchaConfigResponse(
            boolean enabled,
            @JsonProperty("site_key") String siteKey) {
    }
}
