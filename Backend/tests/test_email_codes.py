from datetime import UTC, datetime, timedelta

import pytest

from app.services.email_codes import (
    EmailCodeRateLimitError,
    EmailCodeStore,
    InvalidEmailCodeError,
    RedisEmailCodeStore,
    RedisEmailRateLimiter,
)


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, str | int] = {}
        self.ttls: dict[str, int] = {}

    def setex(self, key: str, seconds: int, value: str) -> None:
        self.values[key] = value
        self.ttls[key] = seconds

    def get(self, key: str) -> str | int | None:
        return self.values.get(key)

    def delete(self, key: str) -> None:
        self.values.pop(key, None)
        self.ttls.pop(key, None)

    def incr(self, key: str) -> int:
        self.values[key] = int(self.values.get(key, 0)) + 1
        return int(self.values[key])

    def expire(self, key: str, seconds: int) -> None:
        self.ttls[key] = seconds


def test_email_code_store_uses_configurable_expiration_seconds():
    store = EmailCodeStore()
    issued_at = datetime(2026, 5, 23, 12, 0, tzinfo=UTC)

    record = store.issue_code("student@example.com", now=issued_at, expires_in_seconds=120)

    assert record.expires_at == issued_at + timedelta(seconds=120)


def test_email_code_store_rejects_non_positive_expiration():
    store = EmailCodeStore()

    with pytest.raises(ValueError):
        store.issue_code("student@example.com", expires_in_seconds=0)


def test_email_code_store_rejects_expired_code():
    store = EmailCodeStore()
    issued_at = datetime(2026, 5, 23, 12, 0, tzinfo=UTC)
    record = store.issue_code("student@example.com", now=issued_at, expires_in_seconds=30)

    with pytest.raises(InvalidEmailCodeError):
        store.consume_code("student@example.com", record.code, now=issued_at + timedelta(seconds=31))


def test_redis_email_code_store_hashes_code_and_consumes_once():
    redis = FakeRedis()
    store = RedisEmailCodeStore(redis)
    issued_at = datetime(2026, 5, 23, 12, 0, tzinfo=UTC)

    record = store.issue_code("student@example.com", now=issued_at, expires_in_seconds=120)
    stored_value = redis.get("email_code:student@example.com")

    assert record.code not in str(stored_value)
    assert redis.ttls["email_code:student@example.com"] == 120

    store.consume_code("student@example.com", record.code)

    with pytest.raises(InvalidEmailCodeError):
        store.consume_code("student@example.com", record.code)


def test_redis_email_rate_limiter_blocks_excess_requests():
    redis = FakeRedis()
    limiter = RedisEmailRateLimiter(redis, limit=2, window_seconds=600)

    limiter.check("student@example.com")
    limiter.check("student@example.com")

    with pytest.raises(EmailCodeRateLimitError):
        limiter.check("student@example.com")
