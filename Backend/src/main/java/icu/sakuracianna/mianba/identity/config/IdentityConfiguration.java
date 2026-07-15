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
