package icu.sakuracianna.mianba.identity.service;

import icu.sakuracianna.mianba.platform.config.IdentityProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

/** 生成高熵六位验证码、写入一次性存储并调用受控邮件交付。 */
public class VerificationCodeService {
    private final OneTimeCodeStore store;
    private final VerificationCodeDelivery delivery;
    private final IdentityProperties properties;
    private final SecureRandom random;

    public VerificationCodeService(
            OneTimeCodeStore store,
            VerificationCodeDelivery delivery,
            IdentityProperties properties,
            SecureRandom random) {
        this.store = store;
        this.delivery = delivery;
        this.properties = properties;
        this.random = random;
    }

    /**
     * 为规范化邮箱生成并交付一次性验证码。
     * 邮件交付失败时只条件撤销本次值，避免误删并发请求已经覆盖的新验证码。
     *
     * @param rawEmail 待规范化的邮箱地址
     * @return 有效期信息；仅开发开关启用时包含明文验证码
     */
    public IssuedCode issue(String rawEmail) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String code = "%06d".formatted(random.nextInt(1_000_000));
        store.issue(email, code, Duration.ofSeconds(properties.emailCodeSeconds()));
        try {
            delivery.deliver(email, code, properties.emailCodeSeconds());
        } catch (RuntimeException deliveryFailure) {
            // 发送失败的验证码不能继续留在 Redis 中；条件撤销避免删除并发请求刚写入的新验证码。
            try {
                store.revoke(email, code);
            } catch (RuntimeException revokeFailure) {
                deliveryFailure.addSuppressed(revokeFailure);
            }
            throw deliveryFailure;
        }
        return new IssuedCode(
                email,
                properties.emailCodeSeconds(),
                properties.exposeDevCodes() ? code : "");
    }

    /** 验证码签发结果，不表示邮件一定已被用户接收。 */
    public record IssuedCode(String email, int expiresInSeconds, String devCode) {
    }
}
