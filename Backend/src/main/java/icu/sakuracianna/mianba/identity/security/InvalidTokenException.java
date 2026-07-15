package icu.sakuracianna.mianba.identity.security;

/** JWT 签名、声明或有效期不满足安全要求。 */
public final class InvalidTokenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidTokenException(Throwable cause) {
        super("invalid_token", cause);
    }
}
