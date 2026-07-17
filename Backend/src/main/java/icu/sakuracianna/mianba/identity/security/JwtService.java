package icu.sakuracianna.mianba.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/** 签发并验证短期 HS256 访问令牌，服务端会话有效性由过滤器另行校验。 */
public final class JwtService {
    private final byte[] secret;
    private final Clock clock;

    public JwtService(String secret, Clock clock) {
        this.secret = Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 签发带用户认证版本和服务端会话标识的短期访问令牌。
     *
     * @param authVersion 签发时的账号认证版本，改密后用于使旧令牌失效
     * @param lifetime 令牌有效时长
     * @return HS256 紧凑序列化令牌
     */
    public String issue(
            UUID userId,
            String email,
            String role,
            UUID sessionId,
            long authVersion,
            Duration lifetime) {
        Instant issuedAt = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim("sid", sessionId.toString())
                .claim("ver", authVersion)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(lifetime)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign JWT", exception);
        }
    }

    /**
     * 验证签名和有效期，并解析安全上下文所需的最小声明。
     * 服务端会话、账号启用状态和当前认证版本由认证过滤器继续校验。
     *
     * @throws InvalidTokenException 令牌损坏、签名无效、声明非法或已过期时抛出
     */
    public AuthenticatedUser verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secret))) {
                throw new JOSEException("signature verification failed");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            if (expiration == null || !expiration.toInstant().isAfter(clock.instant())) {
                throw new JOSEException("token expired");
            }
            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    claims.getStringClaim("email"),
                    claims.getStringClaim("role"),
                    UUID.fromString(claims.getStringClaim("sid")),
                    claims.getLongClaim("ver"));
        } catch (JOSEException | ParseException | IllegalArgumentException exception) {
            throw new InvalidTokenException(exception);
        }
    }
}
