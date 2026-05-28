import unittest

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.models.base import Base
from app.models.entities import User
from app.services.user_credentials import (
    DatabaseUserCredentialStore,
    InMemoryUserCredentialStore,
    UserAccountRecord,
    _memory_users,
)


class UserCredentialStoreTests(unittest.TestCase):
    def tearDown(self) -> None:
        _memory_users.clear()

    def test_in_memory_create_user_preserves_existing_admin_role(self) -> None:
        _memory_users["admin@example.com"] = UserAccountRecord(email="admin@example.com", role="admin")
        store = InMemoryUserCredentialStore()

        store.create_user("ADMIN@example.com", "hashed-password")

        record = store.get_user_record("admin@example.com")
        self.assertIsNotNone(record)
        self.assertEqual(record.role, "admin")
        self.assertEqual(record.password_hash, "hashed-password")

    def test_database_create_user_preserves_existing_admin_role(self) -> None:
        engine = create_engine("sqlite:///:memory:")
        Base.metadata.create_all(engine)
        SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)

        with SessionLocal() as session:
            session.add(
                User(
                    email="admin@example.com",
                    password_hash=None,
                    role="admin",
                    credit_balance=3,
                    is_active=True,
                )
            )
            session.commit()

            store = DatabaseUserCredentialStore(session)
            store.create_user("admin@example.com", "hashed-password", initial_credit_balance=9)

            record = store.get_user_record("admin@example.com")
            self.assertIsNotNone(record)
            self.assertEqual(record.role, "admin")
            self.assertEqual(record.password_hash, "hashed-password")


if __name__ == "__main__":
    unittest.main()
