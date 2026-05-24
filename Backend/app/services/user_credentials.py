from typing import Any

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import User
from app.services.redis_runtime import get_redis_client

_memory_password_hashes: dict[str, str] = {}


class UserAlreadyExistsError(ValueError):
    """Raised when a user attempts to register an email that already exists."""


class UserNotFoundError(LookupError):
    """Raised when password login cannot find the user email."""


class UserCredentialStore:
    def create_placeholder_user(self, email: str) -> None:
        raise NotImplementedError

    def create_user(self, email: str, password_hash: str) -> None:
        raise NotImplementedError

    def get_password_hash(self, email: str) -> str | None:
        raise NotImplementedError

    def require_password_hash(self, email: str) -> str:
        password_hash = self.get_password_hash(email)
        if password_hash is None:
            raise UserNotFoundError("user not found")
        return password_hash


class InMemoryUserCredentialStore(UserCredentialStore):
    def create_placeholder_user(self, email: str) -> None:
        return

    def create_user(self, email: str, password_hash: str) -> None:
        if email in _memory_password_hashes:
            raise UserAlreadyExistsError("email already registered")
        _memory_password_hashes[email] = password_hash

    def get_password_hash(self, email: str) -> str | None:
        return _memory_password_hashes.get(email)


class RedisUserCredentialStore(UserCredentialStore):
    def __init__(self, redis_client: Any, key_prefix: str = "user_password_hash") -> None:
        self._redis = redis_client
        self._key_prefix = key_prefix

    def create_placeholder_user(self, email: str) -> None:
        return

    def create_user(self, email: str, password_hash: str) -> None:
        created = self._redis.set(self._key(email), password_hash, nx=True)
        if not created:
            raise UserAlreadyExistsError("email already registered")

    def get_password_hash(self, email: str) -> str | None:
        value = self._redis.get(self._key(email))
        if isinstance(value, bytes):
            return value.decode("utf-8")
        return value

    def _key(self, email: str) -> str:
        return f"{self._key_prefix}:{email}"


class DatabaseUserCredentialStore(UserCredentialStore):
    def __init__(self, session: Session) -> None:
        self._session = session

    def create_placeholder_user(self, email: str) -> None:
        normalized_email = self._normalize_email(email)
        existing = self._get_user(normalized_email)
        if existing is not None:
            return
        self._session.add(User(email=normalized_email, password_hash=None, role="user", credit_balance=0, is_active=True))
        self._session.commit()

    def create_user(self, email: str, password_hash: str) -> None:
        normalized_email = self._normalize_email(email)
        existing = self._get_user(normalized_email)
        if existing is None:
            self._session.add(User(email=normalized_email, password_hash=password_hash, role="user", credit_balance=0, is_active=True))
            self._session.commit()
            return
        if existing.password_hash:
            raise UserAlreadyExistsError("email already registered")
        existing.password_hash = password_hash
        existing.is_active = True
        self._session.commit()

    def get_password_hash(self, email: str) -> str | None:
        user = self._get_user(self._normalize_email(email))
        if user is None or not user.is_active:
            return None
        return user.password_hash

    def _get_user(self, email: str) -> User | None:
        return self._session.execute(select(User).where(User.email == email)).scalar_one_or_none()

    def _normalize_email(self, email: str) -> str:
        return email.strip().lower()


def get_optional_user_db_session():
    yield from get_optional_db_session(("users",))


def get_user_credential_store(db_session: Session | None = Depends(get_optional_user_db_session)) -> UserCredentialStore:
    if db_session is not None:
        return DatabaseUserCredentialStore(db_session)

    redis_client = get_redis_client()
    if redis_client is None:
        return InMemoryUserCredentialStore()
    return RedisUserCredentialStore(redis_client)
