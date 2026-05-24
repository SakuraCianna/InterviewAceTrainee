import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.models import entities  # noqa: F401
from app.models.base import Base
from app.services.user_credentials import (
    DatabaseUserCredentialStore,
    RedisUserCredentialStore,
    UserAlreadyExistsError,
    UserNotFoundError,
)


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    def set(self, key: str, value: str, nx: bool = False) -> bool:
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    def get(self, key: str) -> str | None:
        return self.values.get(key)


def test_redis_user_credential_store_creates_and_reads_password_hash():
    store = RedisUserCredentialStore(FakeRedis())

    store.create_user("student@example.com", "hashed-password")

    assert store.get_password_hash("student@example.com") == "hashed-password"


def test_redis_user_credential_store_rejects_duplicate_email():
    store = RedisUserCredentialStore(FakeRedis())
    store.create_user("student@example.com", "hash-1")

    with pytest.raises(UserAlreadyExistsError):
        store.create_user("student@example.com", "hash-2")


def test_redis_user_credential_store_raises_for_missing_user():
    store = RedisUserCredentialStore(FakeRedis())

    with pytest.raises(UserNotFoundError):
        store.require_password_hash("missing@example.com")


def test_database_user_credential_store_can_complete_precreated_user_registration():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseUserCredentialStore(session)

    store.create_placeholder_user("student@example.com")
    store.create_user("student@example.com", "hashed-password")

    assert store.get_password_hash("student@example.com") == "hashed-password"


def test_database_user_credential_store_rejects_duplicate_password_user():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseUserCredentialStore(session)

    store.create_user("student@example.com", "hash-1")

    with pytest.raises(UserAlreadyExistsError):
        store.create_user("student@example.com", "hash-2")
