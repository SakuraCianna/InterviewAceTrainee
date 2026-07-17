package icu.sakuracianna.mianba.identity.config;

import icu.sakuracianna.mianba.identity.redis.RedisOneTimeCodeStore;
import icu.sakuracianna.mianba.identity.redis.RedisAbuseProtection;
import icu.sakuracianna.mianba.identity.redis.RedisSessionRegistry;
import icu.sakuracianna.mianba.identity.persistence.JdbcAuthAttemptRecorder;
import icu.sakuracianna.mianba.identity.security.JwtService;
import icu.sakuracianna.mianba.identity.service.Argon2PasswordHasher;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.identity.service.AuthAttemptRecorder;
import icu.sakuracianna.mianba.identity.service.AuthService;
import icu.sakuracianna.mianba.identity.service.InMemoryOneTimeCodeStore;
import icu.sakuracianna.mianba.identity.service.InMemorySessionRegistry;
import icu.sakuracianna.mianba.identity.service.IdentitySettingsProvider;
import icu.sakuracianna.mianba.identity.service.OneTimeCodeStore;
import icu.sakuracianna.mianba.identity.service.PasswordHasher;
import icu.sakuracianna.mianba.identity.service.SessionRegistry;
import icu.sakuracianna.mianba.identity.service.TrialVoucherIssuer;
import icu.sakuracianna.mianba.identity.service.UserAccountRepository;
import icu.sakuracianna.mianba.identity.service.VerificationCodeDelivery;
import icu.sakuracianna.mianba.identity.service.VerificationCodeService;
import icu.sakuracianna.mianba.platform.config.IdentityProperties;
import icu.sakuracianna.mianba.platform.config.SecurityProperties;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 注册密码、验证码交付、会话、限流和认证审计等身份基础设施。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class IdentityConfiguration {

    @Bean
    AuthAttemptRecorder authAttemptRecorder(JdbcClient jdbc) {
        return new JdbcAuthAttemptRecorder(jdbc);
    }

    @Bean
    AbuseProtection abuseProtection(StringRedisTemplate redis, Clock clock) {
        return new RedisAbuseProtection(redis, clock);
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new Argon2PasswordHasher();
    }

    @Bean
    JwtService jwtService(SecurityProperties properties, Clock clock) {
        return new JwtService(properties.jwtSecret(), clock);
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    AuthService authService(
            UserAccountRepository users,
            OneTimeCodeStore codes,
            PasswordHasher passwords,
            SessionRegistry sessions,
            TrialVoucherIssuer vouchers,
            IdentitySettingsProvider settings,
            IdentityProperties properties) {
        return new AuthService(users, codes, passwords, sessions, vouchers, settings,
                Duration.ofMinutes(properties.accessTokenMinutes()));
    }

    @Bean
    VerificationCodeService verificationCodeService(
            OneTimeCodeStore store,
            VerificationCodeDelivery delivery,
            IdentityProperties properties,
            SecureRandom random) {
        return new VerificationCodeService(store, delivery, properties, random);
    }

    @Bean
    VerificationCodeDelivery verificationCodeDelivery(
            org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSenderProvider,
            RestClient.Builder restClientBuilder,
            IdentityProperties properties,
            SecureRandom random) {
        byte[] idempotencySalt = new byte[32];
        random.nextBytes(idempotencySalt);
        RestClient resendClient = restClientBuilder
                .baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer " + properties.resendApiKey())
                .defaultHeader("User-Agent", "mianba-backend/1.0")
                .build();
        return (email, code, expiresInSeconds) -> {
            if (properties.exposeDevCodes()) {
                return;
            }
            if (!properties.resendApiKey().isBlank()) {
                if (properties.mailFrom().isBlank()) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "email_from_address_missing", "邮件发件地址未配置");
                }
                try {
                    resendClient.post()
                            .uri("/emails")
                            .header("Idempotency-Key", verificationIdempotencyKey(
                                    idempotencySalt, email, code))
                            .body(java.util.Map.of(
                                    "from", properties.mailFrom(),
                                    "to", java.util.List.of(email),
                                    "subject", "面霸练习生验证码",
                                    "html", buildHtmlEmail(code, expiresInSeconds),
                                    "text", "您的验证码是 " + code + "，" + expiresInSeconds + " 秒内有效。请勿转发。"))
                            .retrieve()
                            .toBodilessEntity();
                    return;
                } catch (RestClientException exception) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "resend_delivery_failed", "验证码邮件发送失败");
                }
            }
            JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
            if (mailSender == null || properties.mailFrom().isBlank()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "email_delivery_unavailable", "邮件发送能力未配置");
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.mailFrom());
            message.setTo(email);
            message.setSubject("面霸练习生验证码");
            message.setText("您的验证码是 " + code + "，" + expiresInSeconds + " 秒内有效。请勿转发。");
            mailSender.send(message);
        };
    }

    private static String buildHtmlEmail(String code, int expiresInSeconds) {
        String expiry = expiresInSeconds % 60 == 0
                ? (expiresInSeconds / 60) + " 分钟"
                : expiresInSeconds + " 秒";
        return ("""
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"></head>
                <body style="margin:0;padding:0;background:#f7f4ec;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;">
                <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f7f4ec;padding:48px 16px;">
                  <tr><td align="center">
                    <table width="540" cellpadding="0" cellspacing="0" style="max-width:540px;width:100%%;border-radius:14px;overflow:hidden;border:1px solid rgba(20,24,32,0.1);box-shadow:0 20px 60px rgba(20,24,32,0.10);">
                      <tr>
                        <td style="background:#141820;padding:26px 36px;">
                          <p style="margin:0;color:#cbe85b;font-size:13px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;">MIANBA TRAINEE</p>
                          <p style="margin:6px 0 0;color:#ffffff;font-size:19px;font-weight:800;">面霸练习生</p>
                        </td>
                      </tr>
                      <tr>
                        <td style="background:#fffdf7;padding:38px 36px 30px;">
                          <p style="margin:0 0 6px;color:#566170;font-size:13px;font-weight:700;letter-spacing:0.06em;text-transform:uppercase;">安全验证码</p>
                          <h1 style="margin:0 0 28px;color:#141820;font-size:26px;font-weight:800;line-height:1.22;">这是你的登录 / 注册验证码</h1>
                          <div style="background:#eef4ff;border:1px solid rgba(36,104,242,0.18);border-radius:12px;padding:30px 24px;text-align:center;margin-bottom:26px;">
                            <p style="margin:0 0 8px;color:#566170;font-size:12px;font-weight:700;letter-spacing:0.1em;text-transform:uppercase;">验证码</p>
                            <p style="margin:0;color:#2468f2;font-size:52px;font-weight:900;letter-spacing:12px;font-variant-numeric:tabular-nums;">%s</p>
                          </div>
                          <p style="margin:0 0 20px;color:#566170;font-size:15px;line-height:1.72;">该验证码将在 <strong style="color:#141820;">%s</strong> 后失效。如果你没有发起此请求，请忽略这封邮件，账号不会受到任何影响。</p>
                          <div style="border-left:3px solid #e8c547;background:rgba(232,197,71,0.09);border-radius:0 8px 8px 0;padding:13px 16px;">
                            <p style="margin:0;color:#141820;font-size:14px;line-height:1.65;"><strong>安全提示：</strong>面霸练习生不会通过电话、短信或任何渠道索要验证码，请勿转发给任何人。</p>
                          </div>
                        </td>
                      </tr>
                      <tr>
                        <td style="background:#f7f4ec;padding:18px 36px;border-top:1px solid rgba(20,24,32,0.08);">
                          <p style="margin:0;color:#9aa4b0;font-size:12px;line-height:1.6;">此邮件由面霸练习生系统自动发送，请勿直接回复。报告用于训练复盘，不承诺录取或考试结果。</p>
                        </td>
                      </tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """).formatted(code, expiry);
    }

    private static String verificationIdempotencyKey(byte[] salt, String email, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update((byte) 0);
            digest.update(email.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return "verification-" + HexFormat.of().formatHex(
                    digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "mianba.runtime.local-fallback-enabled", havingValue = "true")
    OneTimeCodeStore localOneTimeCodeStore(Clock clock) {
        return new InMemoryOneTimeCodeStore(clock);
    }

    @Bean
    @ConditionalOnProperty(name = "mianba.runtime.local-fallback-enabled", havingValue = "false", matchIfMissing = true)
    OneTimeCodeStore redisOneTimeCodeStore(StringRedisTemplate redis) {
        return new RedisOneTimeCodeStore(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "mianba.runtime.local-fallback-enabled", havingValue = "true")
    SessionRegistry localSessionRegistry(Clock clock) {
        return new InMemorySessionRegistry(clock);
    }

    @Bean
    @ConditionalOnProperty(name = "mianba.runtime.local-fallback-enabled", havingValue = "false", matchIfMissing = true)
    SessionRegistry redisSessionRegistry(StringRedisTemplate redis) {
        return new RedisSessionRegistry(redis);
    }
}
