package icu.sakuracianna.mianba.identity.service;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/** 认证应用服务，集中处理验证码、密码哈希和会话撤销的事务边界。 */
public class AuthService {
    /**
     * 未知账号校验使用的固定合法 Argon2id 哈希。
     *
     * 该值仅用于拉平失败路径的计算成本，不对应任何可登录账号，也不得在请求期间重新生成。
     */
    static final String PASSWORD_TIMING_HASH = "$argon2id$v=19$m=16384,t=2,p=1$"
            + "TbfPy22Wyg9DZHP/22s3ew$0aTywB5ntAHnjGw/6tTQ00NqM6o8E1zNS+IcX6Qfo5A";

    private final UserAccountRepository users;
    private final OneTimeCodeStore codes;
    private final PasswordHasher passwords;
    private final SessionRegistry sessions;
    private final TrialVoucherIssuer vouchers;
    private final IdentitySettingsProvider settings;
    private final Duration sessionTtl;

    public AuthService(
            UserAccountRepository users,
            OneTimeCodeStore codes,
            PasswordHasher passwords,
            SessionRegistry sessions,
            TrialVoucherIssuer vouchers,
            IdentitySettingsProvider settings,
            Duration sessionTtl) {
        this.users = users;
        this.codes = codes;
        this.passwords = passwords;
        this.sessions = sessions;
        this.vouchers = vouchers;
        this.settings = settings;
        this.sessionTtl = sessionTtl;
    }

    /**
     * 使用邮箱验证码注册账号，并在同一事务内发放初始积分和体验券。
     * 验证码必须先消费，避免同一凭据被并发注册请求重复使用。
     *
     * @return 新账号及服务端会话标识
     */
    @Transactional
    public LoginSession register(String rawEmail, String password, String code) {
        IdentitySettings current = settings.current();
        requireEnabled(current.registrationOpen(), "registration_closed", "当前暂未开放新用户注册");
        String email = normalizeEmail(rawEmail);
        requireCode(email, code);
        users.findByEmail(email).ifPresent(existing -> {
            if ("admin".equals(existing.role())) {
                throw failure(HttpStatus.FORBIDDEN, "admin_login_required", "管理员请使用管理端登录");
            }
            throw failure(HttpStatus.CONFLICT, "email_already_registered", "该邮箱已注册");
        });
        UserAccount created = users.create(
                email, passwords.hash(password), current.newUserDefaultCredits());
        vouchers.issueRegistrationVouchers(created.id(), current.newUserTrialVouchers());
        return createSession(created);
    }

    /**
     * 校验普通用户密码并创建服务端会话。
     * 账号行锁使同一账号的登录和改密按数据库事务顺序执行，避免签发基于旧认证版本的会话。
     */
    @Transactional
    public LoginSession passwordLogin(String rawEmail, String password) {
        requireEnabled(settings.current().passwordLoginEnabled(),
                "password_login_disabled", "用户密码登录当前不可用");
        String email = normalizeEmail(rawEmail);
        UserAccount user = requirePasswordUserForUpdate(email, password);
        requireActive(user);
        if ("admin".equals(user.role())) {
            throw failure(HttpStatus.FORBIDDEN, "admin_login_required", "管理员请使用管理端登录");
        }
        return createSession(user);
    }

    /**
     * 使用一次性邮箱验证码登录；账号不存在且开放注册时自动完成注册。
     * 自动注册、赠送权益和会话创建共用事务，任一步失败均不得留下半成品账号。
     */
    @Transactional
    public LoginSession emailCodeLogin(String rawEmail, String code) {
        IdentitySettings current = settings.current();
        requireEnabled(current.emailCodeLoginEnabled(),
                "email_code_login_disabled", "邮箱验证码登录当前不可用");
        String email = normalizeEmail(rawEmail);
        // 无效验证码路径不得在账号查询或注册开关处提前返回，否则可通过错误码枚举邮箱。
        requireCode(email, code);
        UserAccount user = users.findByEmailForUpdate(email).orElseGet(() -> {
            requireEnabled(current.registrationOpen(),
                    "registration_closed", "当前暂未开放新用户注册");
            UserAccount created = users.create(email, null, current.newUserDefaultCredits());
            vouchers.issueRegistrationVouchers(created.id(), current.newUserTrialVouchers());
            return created;
        });
        requireActive(user);
        if ("admin".equals(user.role())) {
            throw failure(HttpStatus.FORBIDDEN, "admin_login_required", "管理员请使用管理端登录");
        }
        return createSession(user);
    }

    /**
     * 使用密码与邮箱验证码完成管理员双因素登录。
     * 普通用户即使持有有效验证码也不能通过该入口提升权限。
     */
    @Transactional
    public LoginSession adminLogin(String rawEmail, String password, String code) {
        String email = normalizeEmail(rawEmail);
        UserAccount user = requirePasswordUserForUpdate(email, password);
        requireActive(user);
        if (!"admin".equals(user.role())) {
            throw failure(HttpStatus.FORBIDDEN, "admin_role_required", "当前账号不是管理员");
        }
        requireCode(email, code);
        return createSession(user);
    }

    /**
     * 查询并校验当前账号仍处于启用状态。
     *
     * @param userId 当前认证用户标识
     * @return 最新账号快照
     */
    public UserAccount requireCurrent(UUID userId) {
        UserAccount account = users.findById(userId)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));
        requireActive(account);
        return account;
    }

    /** 返回当前仍可使用且未过期的体验券总次数。 */
    public int availableVoucherUses(UUID userId) {
        return users.availableVoucherUses(userId);
    }

    /** 撤销当前服务端会话，不影响该用户的其他设备。 */
    public void logout(UUID userId, UUID sessionId) {
        sessions.revoke(userId, sessionId);
    }

    /**
     * 更新密码并撤销全部旧会话。
     * 该方法仅供已经完成额外身份校验的内部流程调用。
     */
    @Transactional
    public void changePassword(UUID userId, String newPassword) {
        UserAccount user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));
        requireActive(user);
        users.updatePassword(userId, passwords.hash(newPassword));
        sessions.revokeAll(userId);
    }

    /** 已登录改密仍需邮箱验证码，改密后撤销该用户的全部旧会话。 */
    @Transactional
    public void changePasswordWithCode(UUID userId, String code, String newPassword) {
        UserAccount user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));
        requireActive(user);
        requireCode(user.email(), code);
        users.updatePassword(userId, passwords.hash(newPassword));
        sessions.revokeAll(userId);
    }

    /**
     * 未登录找回密码采用同一一次性验证码存储。
     * 验证码校验必须先于账号查询，避免无效验证码请求通过响应差异枚举已注册邮箱。
     */
    @Transactional
    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = normalizeEmail(rawEmail);
        requireCode(email, code);
        UserAccount user = users.findByEmailForUpdate(email)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));
        requireActive(user);
        users.updatePassword(user.id(), passwords.hash(newPassword));
        sessions.revokeAll(user.id());
    }

    private LoginSession createSession(UserAccount user) {
        UUID sessionId = UUID.randomUUID();
        sessions.create(user.id(), sessionId, sessionTtl);
        return new LoginSession(user, sessionId);
    }

    private UserAccount requirePasswordUserForUpdate(String email, String rawPassword) {
        UserAccount user = users.findByEmailForUpdate(email).orElse(null);
        // 未知账号和仅支持验证码的账号也执行真实 Argon2 校验，避免短路形成可测量的计时差。
        String encodedPassword = user == null || user.passwordHash() == null
                ? PASSWORD_TIMING_HASH
                : user.passwordHash();
        boolean matched = passwords.matches(rawPassword, encodedPassword);
        if (user == null || user.passwordHash() == null || !matched) {
            throw invalidCredentials();
        }
        return user;
    }

    private void requireCode(String email, String code) {
        if (!codes.consume(email, code)) {
            throw failure(HttpStatus.UNAUTHORIZED, "invalid_email_code", "验证码无效或已过期");
        }
    }

    private static void requireActive(UserAccount user) {
        if (!user.active()) {
            throw failure(HttpStatus.FORBIDDEN, "user_disabled", "账号已被停用");
        }
    }

    private static void requireEnabled(boolean enabled, String detail, String message) {
        if (!enabled) {
            throw failure(HttpStatus.FORBIDDEN, detail, message);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCredentials() {
        return failure(HttpStatus.UNAUTHORIZED, "invalid_credentials", "邮箱或密码不正确");
    }

    private static ApiException failure(HttpStatus status, String detail, String message) {
        return new ApiException(status, detail, message);
    }
}
