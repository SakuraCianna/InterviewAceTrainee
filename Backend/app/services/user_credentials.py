from dataclasses import dataclass
from typing import Any

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import User
from app.services.redis_runtime import get_redis_client

@dataclass
class UserAccountRecord:
    email: str
    role: str = "user"
    is_active: bool = True
    password_hash: str | None = None


_memory_users: dict[str, UserAccountRecord] = {}


class UserAlreadyExistsError(ValueError):
    """Raised when a user attempts to register an email that already exists."""


class UserNotFoundError(LookupError):
    """Raised when password login cannot find the user email."""


class UserDisabledError(PermissionError):
    """Raised when an inactive user attempts to authenticate or register again."""


class UserCredentialStore:
    def create_placeholder_user(self, email: str, initial_credit_balance: int = 0) -> None:
        raise NotImplementedError

    def create_user(self, email: str, password_hash: str, initial_credit_balance: int = 0) -> None:
        raise NotImplementedError

    def get_password_hash(self, email: str) -> str | None:
        raise NotImplementedError

    def user_exists(self, email: str) -> bool:
        raise NotImplementedError

    def is_active(self, email: str) -> bool:
        raise NotImplementedError

    def set_active(self, email: str, is_active: bool) -> bool:
        raise NotImplementedError

    def search_users(self, query: str, limit: int = 20) -> list[UserAccountRecord]:
        raise NotImplementedError

    def require_password_hash(self, email: str) -> str:
        if not self.is_active(email):
            raise UserDisabledError("user disabled")
        password_hash = self.get_password_hash(email)
        if password_hash is None:
            raise UserNotFoundError("user not found")
        return password_hash


class InMemoryUserCredentialStore(UserCredentialStore):
    def create_placeholder_user(self, email: str, initial_credit_balance: int = 0) -> None:
        normalized_email = self._normalize_email(email)
        existing = _memory_users.get(normalized_email)
        if existing is not None:
            if not existing.is_active:
                raise UserDisabledError("user disabled")
            return
        _memory_users[normalized_email] = UserAccountRecord(email=normalized_email)

    def create_user(self, email: str, password_hash: str, initial_credit_balance: int = 0) -> None:
        normalized_email = self._normalize_email(email)
        existing = _memory_users.get(normalized_email)
        if existing is not None and not existing.is_active:
            raise UserDisabledError("user disabled")
        if existing is not None and existing.password_hash:
            raise UserAlreadyExistsError("email already registered")
        _memory_users[normalized_email] = UserAccountRecord(email=normalized_email, password_hash=password_hash)

    def get_password_hash(self, email: str) -> str | None:
        user = _memory_users.get(self._normalize_email(email))
        if user is None or not user.is_active:
            return None
        return user.password_hash

    def user_exists(self, email: str) -> bool:
        return self._normalize_email(email) in _memory_users

    def is_active(self, email: str) -> bool:
        user = _memory_users.get(self._normalize_email(email))
        return True if user is None else user.is_active

    def set_active(self, email: str, is_active: bool) -> bool:
        normalized_email = self._normalize_email(email)
        user = _memory_users.get(normalized_email)
        if user is None:
            user = UserAccountRecord(email=normalized_email)
            _memory_users[normalized_email] = user
        user.is_active = is_active
        return user.is_active

    def search_users(self, query: str, limit: int = 20) -> list[UserAccountRecord]:
        normalized_query = self._normalize_email(query)
        return [
            user
            for user in sorted(_memory_users.values(), key=lambda item: item.email)
            if normalized_query in user.email
        ][:limit]

    def _normalize_email(self, email: str) -> str:
        return email.strip().lower()


class RedisUserCredentialStore(UserCredentialStore):
    def __init__(self, redis_client: Any, key_prefix: str = "user_password_hash") -> None:
        self._redis = redis_client
        self._key_prefix = key_prefix

    def create_placeholder_user(self, email: str, initial_credit_balance: int = 0) -> None:
        normalized_email = self._normalize_email(email)
        if not self.is_active(normalized_email):
            raise UserDisabledError("user disabled")
        if not hasattr(self._redis, "hsetnx"):
            return
        self._redis.hsetnx(self._profile_key(normalized_email), "email", normalized_email)
        self._redis.hsetnx(self._profile_key(normalized_email), "role", "user")
        self._redis.hsetnx(self._profile_key(normalized_email), "is_active", "1")

    def create_user(self, email: str, password_hash: str, initial_credit_balance: int = 0) -> None:
        normalized_email = self._normalize_email(email)
        if not self.is_active(normalized_email):
            raise UserDisabledError("user disabled")
        created = self._redis.set(self._key(normalized_email), password_hash, nx=True)
        if not created:
            raise UserAlreadyExistsError("email already registered")
        if hasattr(self._redis, "hset"):
            self._redis.hset(self._profile_key(normalized_email), mapping={"email": normalized_email, "role": "user", "is_active": "1"})

    def get_password_hash(self, email: str) -> str | None:
        normalized_email = self._normalize_email(email)
        if not self.is_active(normalized_email):
            return None
        value = self._redis.get(self._key(normalized_email))
        if isinstance(value, bytes):
            return value.decode("utf-8")
        return value

    def user_exists(self, email: str) -> bool:
        normalized_email = self._normalize_email(email)
        if hasattr(self._redis, "exists"):
            return bool(self._redis.exists(self._key(normalized_email)) or self._redis.exists(self._profile_key(normalized_email)))
        return self._redis.get(self._key(normalized_email)) is not None

    def is_active(self, email: str) -> bool:
        normalized_email = self._normalize_email(email)
        if not hasattr(self._redis, "hget"):
            return True
        value = self._redis.hget(self._profile_key(normalized_email), "is_active")
        if value is None:
            return True
        if isinstance(value, bytes):
            value = value.decode("utf-8")
        return value == "1"

    def set_active(self, email: str, is_active: bool) -> bool:
        normalized_email = self._normalize_email(email)
        if not hasattr(self._redis, "hset"):
            return is_active
        self._redis.hset(self._profile_key(normalized_email), mapping={"email": normalized_email, "role": "user", "is_active": "1" if is_active else "0"})
        return is_active

    def search_users(self, query: str, limit: int = 20) -> list[UserAccountRecord]:
        normalized_query = self._normalize_email(query)
        if not hasattr(self._redis, "scan_iter"):
            return []
        records: list[UserAccountRecord] = []
        for key in self._redis.scan_iter(f"{self._key_prefix}:profile:*", count=200):
            profile = self._redis.hgetall(key)
            email = profile.get(b"email") or profile.get("email")
            if isinstance(email, bytes):
                email = email.decode("utf-8")
            if not isinstance(email, str) or normalized_query not in email:
                continue
            active = profile.get(b"is_active") or profile.get("is_active") or "1"
            if isinstance(active, bytes):
                active = active.decode("utf-8")
            records.append(UserAccountRecord(email=email, is_active=active == "1"))
            if len(records) >= limit:
                break
        return records

    def _key(self, email: str) -> str:
        return f"{self._key_prefix}:{email}"

    def _profile_key(self, email: str) -> str:
        return f"{self._key_prefix}:profile:{email}"

    def _normalize_email(self, email: str) -> str:
        return email.strip().lower()


class DatabaseUserCredentialStore(UserCredentialStore):
    def __init__(self, session: Session) -> None:
        self._session = session

    def create_placeholder_user(self, email: str, initial_credit_balance: int = 0) -> None:
        normalized_email = self._normalize_email(email)
        existing = self._get_user(normalized_email)
        if existing is not None:
            if not existing.is_active:
                raise UserDisabledError("user disabled")
            return
        self._session.add(User(email=normalized_email, password_hash=None, role="user", credit_balance=initial_credit_balance, is_active=True))
        self._session.commit()

    def create_user(self, email: str, password_hash: str, initial_credit_balance: int = 0) -> None:
        normalized_email = self._normalize_email(email)
        existing = self._get_user(normalized_email)
        if existing is None:
            self._session.add(User(email=normalized_email, password_hash=password_hash, role="user", credit_balance=initial_credit_balance, is_active=True))
            self._session.commit()
            return
        if not existing.is_active:
            raise UserDisabledError("user disabled")
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

    def user_exists(self, email: str) -> bool:
        return self._get_user(self._normalize_email(email)) is not None

    def is_active(self, email: str) -> bool:
        user = self._get_user(self._normalize_email(email))
        return True if user is None else user.is_active

    def set_active(self, email: str, is_active: bool) -> bool:
        normalized_email = self._normalize_email(email)
        user = self._get_user(normalized_email)
        if user is None:
            user = User(email=normalized_email, password_hash=None, role="user", credit_balance=0, is_active=is_active)
            self._session.add(user)
        else:
            user.is_active = is_active
        self._session.commit()
        return user.is_active

    def search_users(self, query: str, limit: int = 20) -> list[UserAccountRecord]:
        normalized_query = f"%{self._normalize_email(query)}%"
        users = self._session.execute(
            select(User).where(User.email.ilike(normalized_query)).order_by(User.created_at.desc()).limit(limit)
        ).scalars()
        return [
            UserAccountRecord(
                email=user.email,
                role=user.role,
                is_active=user.is_active,
                password_hash=user.password_hash,
            )
            for user in users
        ]

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
