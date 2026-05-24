from typing import Any

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import User
from app.services.credits import InsufficientCreditsError
from app.services.redis_runtime import get_redis_client

_memory_balances: dict[str, int] = {}


class CreditBalanceStore:
    def get_balance(self, user_id: str) -> int:
        raise NotImplementedError

    def adjust(self, user_id: str, change_amount: int) -> int:
        raise NotImplementedError


class InMemoryCreditBalanceStore(CreditBalanceStore):
    def get_balance(self, user_id: str) -> int:
        return _memory_balances.get(user_id, 0)

    def adjust(self, user_id: str, change_amount: int) -> int:
        current_balance = self.get_balance(user_id)
        balance_after = current_balance + change_amount
        if balance_after < 0:
            raise InsufficientCreditsError("credit balance cannot be negative")
        _memory_balances[user_id] = balance_after
        return balance_after


class RedisCreditBalanceStore(CreditBalanceStore):
    def __init__(self, redis_client: Any, key_prefix: str = "user_credit") -> None:
        self._redis = redis_client
        self._key_prefix = key_prefix

    def get_balance(self, user_id: str) -> int:
        value = self._redis.get(self._key(user_id))
        if value is None:
            return 0
        return int(value)

    def adjust(self, user_id: str, change_amount: int) -> int:
        result = self._redis.eval(
            """
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local next_value = current + tonumber(ARGV[1])
            if next_value < 0 then
              return nil
            end
            redis.call('SET', KEYS[1], next_value)
            return next_value
            """,
            1,
            self._key(user_id),
            change_amount,
        )
        if result is None:
            raise InsufficientCreditsError("credit balance cannot be negative")
        return int(result)

    def _key(self, user_id: str) -> str:
        return f"{self._key_prefix}:{user_id}"


class DatabaseCreditBalanceStore(CreditBalanceStore):
    def __init__(self, session: Session) -> None:
        self._session = session

    def get_balance(self, user_id: str) -> int:
        user = self._get_user(user_id)
        return 0 if user is None else user.credit_balance

    def adjust(self, user_id: str, change_amount: int) -> int:
        user = self._get_or_create_user(user_id)
        balance_after = user.credit_balance + change_amount
        if balance_after < 0:
            self._session.rollback()
            raise InsufficientCreditsError("credit balance cannot be negative")
        user.credit_balance = balance_after
        self._session.commit()
        return balance_after

    def _get_user(self, email: str) -> User | None:
        normalized_email = self._normalize_email(email)
        return self._session.execute(select(User).where(User.email == normalized_email)).scalar_one_or_none()

    def _get_or_create_user(self, email: str) -> User:
        normalized_email = self._normalize_email(email)
        user = self._get_user(normalized_email)
        if user is not None:
            return user
        user = User(email=normalized_email, password_hash=None, role="user", credit_balance=0, is_active=True)
        self._session.add(user)
        self._session.flush()
        return user

    def _normalize_email(self, email: str) -> str:
        return email.strip().lower()


def get_optional_credit_db_session():
    yield from get_optional_db_session(("users",))


def get_credit_balance_store(db_session: Session | None = Depends(get_optional_credit_db_session)) -> CreditBalanceStore:
    if db_session is not None:
        return DatabaseCreditBalanceStore(db_session)

    redis_client = get_redis_client()
    if redis_client is None:
        return InMemoryCreditBalanceStore()
    return RedisCreditBalanceStore(redis_client)
