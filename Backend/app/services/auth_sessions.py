from __future__ import annotations

from secrets import token_urlsafe
from time import time
from typing import Any

from app.services.redis_runtime import get_redis_client

_memory_auth_sessions: dict[str, tuple[str, float]] = {}


class AuthSessionStore:
    def create_session(self, email: str, ttl_seconds: int) -> str:
        raise NotImplementedError

    def is_current_session(self, email: str, session_id: str) -> bool:
        raise NotImplementedError

    def clear_session(self, email: str, session_id: str | None = None) -> None:
        raise NotImplementedError


class InMemoryAuthSessionStore(AuthSessionStore):
    def create_session(self, email: str, ttl_seconds: int) -> str:
        normalized_email = self._normalize_email(email)
        session_id = self._new_session_id()
        _memory_auth_sessions[normalized_email] = (session_id, time() + max(ttl_seconds, 1))
        return session_id

    def is_current_session(self, email: str, session_id: str) -> bool:
        normalized_email = self._normalize_email(email)
        record = _memory_auth_sessions.get(normalized_email)
        if record is None:
            return False
        current_session_id, expires_at = record
        if expires_at <= time():
            _memory_auth_sessions.pop(normalized_email, None)
            return False
        return current_session_id == session_id

    def clear_session(self, email: str, session_id: str | None = None) -> None:
        normalized_email = self._normalize_email(email)
        if session_id is not None and not self.is_current_session(normalized_email, session_id):
            return
        _memory_auth_sessions.pop(normalized_email, None)

    def _normalize_email(self, email: str) -> str:
        return email.strip().lower()

    def _new_session_id(self) -> str:
        return token_urlsafe(32)


class RedisAuthSessionStore(AuthSessionStore):
    def __init__(self, redis_client: Any, key_prefix: str = "auth_session") -> None:
        self._redis = redis_client
        self._key_prefix = key_prefix

    def create_session(self, email: str, ttl_seconds: int) -> str:
        normalized_email = self._normalize_email(email)
        session_id = token_urlsafe(32)
        self._redis.set(self._key(normalized_email), session_id, ex=max(ttl_seconds, 1))
        return session_id

    def is_current_session(self, email: str, session_id: str) -> bool:
        current_session_id = self._redis.get(self._key(self._normalize_email(email)))
        if isinstance(current_session_id, bytes):
            current_session_id = current_session_id.decode("utf-8")
        return current_session_id == session_id

    def clear_session(self, email: str, session_id: str | None = None) -> None:
        key = self._key(self._normalize_email(email))
        if session_id is not None and not self.is_current_session(email, session_id):
            return
        self._redis.delete(key)

    def _key(self, email: str) -> str:
        return f"{self._key_prefix}:{email}"

    def _normalize_email(self, email: str) -> str:
        return email.strip().lower()


def get_auth_session_store() -> AuthSessionStore:
    redis_client = get_redis_client()
    if redis_client is None:
        return InMemoryAuthSessionStore()
    return RedisAuthSessionStore(redis_client)
