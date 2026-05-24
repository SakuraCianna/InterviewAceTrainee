from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from secrets import compare_digest, randbelow
from typing import Any


class InvalidEmailCodeError(ValueError):
    """Raised when an email code is missing, expired, or incorrect."""


class EmailCodeRateLimitError(ValueError):
    """Raised when an email has requested too many verification codes."""


class AuthAttemptRateLimitError(ValueError):
    """Raised when an auth subject has too many failed verification attempts."""


@dataclass(frozen=True)
class EmailCodeRecord:
    email: str
    code: str
    expires_at: datetime


class EmailCodeStore:
    def __init__(self) -> None:
        self._records: dict[str, EmailCodeRecord] = {}

    def issue_code(self, email: str, now: datetime | None = None, expires_in_seconds: int = 600) -> EmailCodeRecord:
        if expires_in_seconds <= 0:
            raise ValueError("email code expiration must be positive")

        issued_at = now or datetime.now(UTC)
        code = generate_email_code()
        record = EmailCodeRecord(email=email, code=code, expires_at=issued_at + timedelta(seconds=expires_in_seconds))
        self._records[email] = record
        return record

    def consume_code(self, email: str, code: str, now: datetime | None = None) -> None:
        checked_at = now or datetime.now(UTC)
        record = self._records.get(email)
        if record is None or record.code != code or record.expires_at < checked_at:
            raise InvalidEmailCodeError("invalid email verification code")
        del self._records[email]


class RedisEmailCodeStore:
    def __init__(self, redis_client: Any, key_prefix: str = "email_code") -> None:
        self._redis = redis_client
        self._key_prefix = key_prefix

    def issue_code(self, email: str, now: datetime | None = None, expires_in_seconds: int = 600) -> EmailCodeRecord:
        if expires_in_seconds <= 0:
            raise ValueError("email code expiration must be positive")

        issued_at = now or datetime.now(UTC)
        code = generate_email_code()
        self._redis.setex(self._key(email), expires_in_seconds, hash_email_code(code))
        return EmailCodeRecord(email=email, code=code, expires_at=issued_at + timedelta(seconds=expires_in_seconds))

    def consume_code(self, email: str, code: str, now: datetime | None = None) -> None:
        key = self._key(email)
        stored_hash = self._redis.get(key)
        if stored_hash is None:
            raise InvalidEmailCodeError("invalid email verification code")

        if isinstance(stored_hash, bytes):
            stored_hash = stored_hash.decode("utf-8")

        if not compare_digest(str(stored_hash), hash_email_code(code)):
            raise InvalidEmailCodeError("invalid email verification code")

        self._redis.delete(key)

    def _key(self, email: str) -> str:
        return f"{self._key_prefix}:{email}"


class RedisEmailRateLimiter:
    def __init__(self, redis_client: Any, limit: int, window_seconds: int, key_prefix: str = "email_code_rate") -> None:
        self._redis = redis_client
        self._limit = limit
        self._window_seconds = window_seconds
        self._key_prefix = key_prefix

    def check(self, email: str) -> None:
        count = self._redis.incr(self._key(email))
        if count == 1:
            self._redis.expire(self._key(email), self._window_seconds)
        if count > self._limit:
            raise EmailCodeRateLimitError("too many email verification code requests")

    def _key(self, email: str) -> str:
        return f"{self._key_prefix}:{email}"


class InMemoryAuthAttemptLimiter:
    def __init__(self) -> None:
        self._attempts: dict[str, tuple[int, datetime]] = {}

    def check(self, key: str, limit: int, window_seconds: int, now: datetime | None = None) -> None:
        checked_at = now or datetime.now(UTC)
        count, expires_at = self._attempts.get(key, (0, checked_at + timedelta(seconds=window_seconds)))
        if expires_at <= checked_at:
            count = 0
            expires_at = checked_at + timedelta(seconds=window_seconds)
        if count >= limit:
            raise AuthAttemptRateLimitError("too many failed auth attempts")
        self._attempts[key] = (count, expires_at)

    def record_failure(self, key: str, limit: int, window_seconds: int, now: datetime | None = None) -> None:
        checked_at = now or datetime.now(UTC)
        count, expires_at = self._attempts.get(key, (0, checked_at + timedelta(seconds=window_seconds)))
        if expires_at <= checked_at:
            count = 0
            expires_at = checked_at + timedelta(seconds=window_seconds)
        self._attempts[key] = (count + 1, expires_at)

    def reset(self, key: str) -> None:
        self._attempts.pop(key, None)


class RedisAuthAttemptLimiter:
    def __init__(self, redis_client: Any, key_prefix: str = "auth_failure") -> None:
        self._redis = redis_client
        self._key_prefix = key_prefix

    def check(self, key: str, limit: int, window_seconds: int) -> None:
        value = self._redis.get(self._key(key))
        count = int(value or 0)
        if count >= limit:
            raise AuthAttemptRateLimitError("too many failed auth attempts")

    def record_failure(self, key: str, limit: int, window_seconds: int) -> None:
        redis_key = self._key(key)
        count = self._redis.incr(redis_key)
        if count == 1:
            self._redis.expire(redis_key, window_seconds)

    def reset(self, key: str) -> None:
        self._redis.delete(self._key(key))

    def _key(self, key: str) -> str:
        return f"{self._key_prefix}:{key}"


def generate_email_code() -> str:
    return str(randbelow(1_000_000)).zfill(6)


def hash_email_code(code: str) -> str:
    return sha256(code.encode("utf-8")).hexdigest()
