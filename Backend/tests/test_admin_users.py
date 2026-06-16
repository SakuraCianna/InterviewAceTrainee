import unittest
from datetime import datetime

from fastapi import FastAPI, HTTPException, status
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.api import admin as admin_api
from app.api.dependencies import require_admin_user
from app.models.base import Base
from app.models.entities import InterviewSession, User
from app.services.credit_balances import InMemoryCreditBalanceStore, _memory_balances
from app.services.interview_runtime import InterviewHistoryRecord
from app.services.user_credentials import InMemoryUserCredentialStore, UserAccountRecord, _memory_users


class FakeInterviewStore:
    def __init__(self, records: dict[str, list[InterviewHistoryRecord]] | None = None) -> None:
        self._records = records or {}

    def list_user_sessions(self, user_email: str, limit: int = 20) -> list[InterviewHistoryRecord]:
        return self._records.get(user_email, [])[:limit]


class ExplodingCreditStore:
    def get_balance(self, user_email: str) -> int:
        raise AssertionError(f"database user listing should not call credit store for {user_email}")


class ExplodingInterviewStore:
    def list_user_sessions(self, user_email: str, limit: int = 20) -> list[InterviewHistoryRecord]:
        raise AssertionError(f"database user listing should not call interview store for {user_email}")


class AdminUsersTests(unittest.TestCase):
    def setUp(self) -> None:
        _memory_users.clear()
        _memory_balances.clear()

    def tearDown(self) -> None:
        _memory_users.clear()
        _memory_balances.clear()

    def test_read_users_returns_recent_user_summaries_without_query(self) -> None:
        _memory_users["alpha@example.com"] = UserAccountRecord(email="alpha@example.com", role="user")
        _memory_users["beta@example.com"] = UserAccountRecord(email="beta@example.com", role="admin", is_active=False)
        _memory_balances["alpha@example.com"] = 7
        interview_store = FakeInterviewStore(
            {
                "alpha@example.com": [
                    InterviewHistoryRecord(
                        session_id="session-1",
                        interview_type="job",
                        status="completed",
                        current_step_index=2,
                        total_steps=3,
                        report_total_score=82,
                        created_at=datetime(2026, 6, 15, 10, 30, 0),
                    )
                ]
            }
        )

        users = admin_api.read_users(
            query=None,
            limit=20,
            offset=0,
            _admin_claims={"sub": "admin@example.com", "role": "admin", "session_id": "session"},
            credit_store=InMemoryCreditBalanceStore(),
            interview_store=interview_store,
            db_session=None,
            user_store=InMemoryUserCredentialStore(),
        )

        self.assertEqual([user.email for user in users.items], ["alpha@example.com", "beta@example.com"])
        self.assertEqual(users.total, 2)
        self.assertEqual(users.limit, 20)
        self.assertEqual(users.offset, 0)
        self.assertFalse(users.has_more)
        self.assertFalse(users.total_is_estimated)
        self.assertEqual(users.items[0].credit_balance, 7)
        self.assertEqual(users.items[0].total_interviews, 1)
        self.assertEqual(users.items[0].completed_interviews, 1)
        self.assertFalse(users.items[1].is_active)
        self.assertEqual(users.items[1].role, "admin")

    def test_read_users_filters_by_query_and_limit(self) -> None:
        _memory_users["alpha@example.com"] = UserAccountRecord(email="alpha@example.com")
        _memory_users["beta@example.com"] = UserAccountRecord(email="beta@example.com")
        _memory_users["beta-two@example.com"] = UserAccountRecord(email="beta-two@example.com")

        users = admin_api.read_users(
            query="beta",
            limit=1,
            offset=0,
            _admin_claims={"sub": "admin@example.com", "role": "admin", "session_id": "session"},
            credit_store=InMemoryCreditBalanceStore(),
            interview_store=FakeInterviewStore(),
            db_session=None,
            user_store=InMemoryUserCredentialStore(),
        )

        self.assertEqual(len(users.items), 1)
        self.assertEqual(users.total, 2)
        self.assertEqual(users.limit, 1)
        self.assertEqual(users.offset, 0)
        self.assertTrue(users.has_more)
        self.assertTrue(users.total_is_estimated)
        self.assertIn("beta", users.items[0].email)

    def test_read_users_fallback_end_page_returns_known_total(self) -> None:
        _memory_users["alpha@example.com"] = UserAccountRecord(email="alpha@example.com")
        _memory_users["beta@example.com"] = UserAccountRecord(email="beta@example.com")
        _memory_users["gamma@example.com"] = UserAccountRecord(email="gamma@example.com")

        users = admin_api.read_users(
            query=None,
            limit=2,
            offset=2,
            _admin_claims={"sub": "admin@example.com", "role": "admin", "session_id": "session"},
            credit_store=InMemoryCreditBalanceStore(),
            interview_store=FakeInterviewStore(),
            db_session=None,
            user_store=InMemoryUserCredentialStore(),
        )

        self.assertEqual([user.email for user in users.items], ["gamma@example.com"])
        self.assertEqual(users.total, 3)
        self.assertEqual(users.limit, 2)
        self.assertEqual(users.offset, 2)
        self.assertFalse(users.has_more)
        self.assertFalse(users.total_is_estimated)

    def test_admin_users_routes_keep_auth_and_specific_paths(self) -> None:
        _memory_users["admin@example.com"] = UserAccountRecord(email="admin@example.com", role="admin")
        _memory_users["alpha@example.com"] = UserAccountRecord(email="alpha@example.com")
        _memory_users["beta@example.com"] = UserAccountRecord(email="beta@example.com")

        def deny_non_admin():
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_required")

        anonymous_app = FastAPI()
        anonymous_app.include_router(admin_api.router, prefix="/api")
        anonymous_app.dependency_overrides[require_admin_user] = deny_non_admin
        anonymous_response = TestClient(anonymous_app).get("/api/admin/users")
        self.assertEqual(anonymous_response.status_code, 403)

        app = FastAPI()
        app.include_router(admin_api.router, prefix="/api")
        app.dependency_overrides[require_admin_user] = lambda: {
            "sub": "admin@example.com",
            "role": "admin",
            "session_id": "session",
        }
        app.dependency_overrides[admin_api.get_credit_balance_store] = lambda: InMemoryCreditBalanceStore()
        app.dependency_overrides[admin_api.get_admin_interview_store] = lambda: FakeInterviewStore()
        app.dependency_overrides[admin_api.get_optional_admin_db_session] = lambda: None
        app.dependency_overrides[admin_api.get_optional_admin_user_list_db_session] = lambda: None
        app.dependency_overrides[admin_api.get_user_credential_store] = lambda: InMemoryUserCredentialStore()
        client = TestClient(app)

        users_response = client.get("/api/admin/users?limit=1")
        self.assertEqual(users_response.status_code, 200)
        users_payload = users_response.json()
        self.assertEqual(len(users_payload["items"]), 1)
        self.assertEqual(users_payload["total"], 2)
        self.assertEqual(users_payload["limit"], 1)
        self.assertEqual(users_payload["offset"], 0)
        self.assertTrue(users_payload["has_more"])
        self.assertTrue(users_payload["total_is_estimated"])

        search_response = client.get("/api/admin/users/search?query=alpha")
        self.assertEqual(search_response.status_code, 200)
        self.assertEqual(search_response.json()[0]["email"], "alpha@example.com")

        detail_response = client.get("/api/admin/users/alpha@example.com")
        self.assertEqual(detail_response.status_code, 200)
        self.assertEqual(detail_response.json()["email"], "alpha@example.com")

    def test_read_users_uses_database_aggregates_without_per_user_store_calls(self) -> None:
        engine = create_engine("sqlite:///:memory:")
        Base.metadata.create_all(engine)
        SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)

        with SessionLocal() as session:
            session.add_all(
                [
                    User(
                        email="alpha@example.com",
                        role="user",
                        credit_balance=7,
                        is_active=True,
                        created_at=datetime(2026, 6, 15, 9, 0, 0),
                    ),
                    User(
                        email="beta@example.com",
                        role="admin",
                        credit_balance=3,
                        is_active=False,
                        created_at=datetime(2026, 6, 15, 10, 0, 0),
                    ),
                    User(
                        email="gamma@example.com",
                        role="user",
                        credit_balance=0,
                        is_active=True,
                        created_at=datetime(2026, 6, 15, 10, 0, 0),
                    ),
                ]
            )
            session.add_all(
                [
                    InterviewSession(
                        id="alpha-1",
                        user_email="alpha@example.com",
                        interview_type="job",
                        status="completed",
                        current_step_index=3,
                        total_steps=3,
                        created_at=datetime(2026, 6, 15, 12, 0, 0),
                    ),
                    InterviewSession(
                        id="alpha-2",
                        user_email="alpha@example.com",
                        interview_type="ielts",
                        status="in_progress",
                        current_step_index=1,
                        total_steps=4,
                        created_at=datetime(2026, 6, 15, 13, 0, 0),
                    ),
                    InterviewSession(
                        id="beta-1",
                        user_email="beta@example.com",
                        interview_type="postgraduate",
                        status="completed",
                        current_step_index=3,
                        total_steps=3,
                        created_at=datetime(2026, 6, 15, 14, 0, 0),
                    ),
                ]
            )
            session.commit()

            first_page = admin_api.read_users(
                query=None,
                limit=2,
                offset=0,
                _admin_claims={"sub": "admin@example.com", "role": "admin", "session_id": "session"},
                credit_store=ExplodingCreditStore(),
                interview_store=ExplodingInterviewStore(),
                db_session=session,
                user_store=InMemoryUserCredentialStore(),
            )

            self.assertEqual([user.email for user in first_page.items], ["beta@example.com", "gamma@example.com"])
            self.assertEqual(first_page.total, 3)
            self.assertEqual(first_page.limit, 2)
            self.assertEqual(first_page.offset, 0)
            self.assertTrue(first_page.has_more)
            self.assertFalse(first_page.total_is_estimated)
            self.assertEqual(first_page.items[0].credit_balance, 3)
            self.assertEqual(first_page.items[0].total_interviews, 1)
            self.assertEqual(first_page.items[0].completed_interviews, 1)
            self.assertEqual(first_page.items[0].last_interview_at, "2026-06-15T14:00:00")
            self.assertEqual(first_page.items[1].credit_balance, 0)
            self.assertEqual(first_page.items[1].total_interviews, 0)

            second_page = admin_api.read_users(
                query=None,
                limit=2,
                offset=2,
                _admin_claims={"sub": "admin@example.com", "role": "admin", "session_id": "session"},
                credit_store=ExplodingCreditStore(),
                interview_store=ExplodingInterviewStore(),
                db_session=session,
                user_store=InMemoryUserCredentialStore(),
            )

            self.assertEqual([user.email for user in second_page.items], ["alpha@example.com"])
            self.assertEqual(second_page.total, 3)
            self.assertEqual(second_page.limit, 2)
            self.assertEqual(second_page.offset, 2)
            self.assertFalse(second_page.has_more)
            self.assertFalse(second_page.total_is_estimated)
            self.assertEqual(second_page.items[0].credit_balance, 7)
            self.assertEqual(second_page.items[0].total_interviews, 2)
            self.assertEqual(second_page.items[0].completed_interviews, 1)
            self.assertEqual(second_page.items[0].last_interview_at, "2026-06-15T13:00:00")

            filtered = admin_api.read_users(
                query="alpha",
                limit=10,
                offset=0,
                _admin_claims={"sub": "admin@example.com", "role": "admin", "session_id": "session"},
                credit_store=ExplodingCreditStore(),
                interview_store=ExplodingInterviewStore(),
                db_session=session,
                user_store=InMemoryUserCredentialStore(),
            )

            self.assertEqual([user.email for user in filtered.items], ["alpha@example.com"])
            self.assertEqual(filtered.total, 1)
            self.assertEqual(filtered.limit, 10)
            self.assertEqual(filtered.offset, 0)
            self.assertFalse(filtered.has_more)
            self.assertFalse(filtered.total_is_estimated)
            self.assertEqual(filtered.items[0].credit_balance, 7)
            self.assertEqual(filtered.items[0].total_interviews, 2)
            self.assertEqual(filtered.items[0].completed_interviews, 1)
            self.assertEqual(filtered.items[0].last_interview_at, "2026-06-15T13:00:00")


if __name__ == "__main__":
    unittest.main()
