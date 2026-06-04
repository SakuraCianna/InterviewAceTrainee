import unittest
from unittest.mock import patch

from fastapi import HTTPException

from app.api.interviews import delete_interview
from app.api.session_guards import require_owned_interview_session
from app.core.config import Settings
from app.db.session import get_optional_db_session
from app.schemas.interviews import InterviewType
from app.services.interview_runtime import InMemoryInterviewRuntimeStore


class SecurityBoundaryTests(unittest.TestCase):
    def test_requires_interview_session_owned_by_current_user(self) -> None:
        store = InMemoryInterviewRuntimeStore()
        store.create_session("owner@example.com", "owned-session", InterviewType.CIVIL_SERVICE)

        state = require_owned_interview_session(
            claims={"sub": "owner@example.com", "role": "user", "session_id": "auth-session"},
            session_id="owned-session",
            interview_store=store,
        )

        self.assertEqual(state.session_id, "owned-session")

        with self.assertRaises(HTTPException) as exc:
            require_owned_interview_session(
                claims={"sub": "attacker@example.com", "role": "user", "session_id": "auth-session"},
                session_id="owned-session",
                interview_store=store,
            )

        self.assertEqual(exc.exception.status_code, 404)
        self.assertEqual(exc.exception.detail, "interview_session_not_found")

    def test_delete_interview_session_requires_owner(self) -> None:
        store = InMemoryInterviewRuntimeStore()
        store.create_session("owner@example.com", "owned-session", InterviewType.CIVIL_SERVICE)

        with self.assertRaises(HTTPException) as exc:
            delete_interview(
                session_id="owned-session",
                claims={"sub": "attacker@example.com", "role": "user", "session_id": "auth-session"},
                interview_store=store,
            )

        self.assertEqual(exc.exception.status_code, 404)
        self.assertIsNotNone(store.get_session("owner@example.com", "owned-session"))

        response = delete_interview(
            session_id="owned-session",
            claims={"sub": "owner@example.com", "role": "user", "session_id": "auth-session"},
            interview_store=store,
        )

        self.assertEqual(response.status_code, 204)
        self.assertIsNone(store.get_session("owner@example.com", "owned-session"))

    def test_database_missing_tables_raise_when_fallback_disabled(self) -> None:
        settings = Settings(database_fallback_enabled=False)

        with (
            patch("app.db.session.get_settings", return_value=settings),
            patch("app.db.session.is_database_ready", return_value=False),
        ):
            db_session = get_optional_db_session(("users",))
            with self.assertRaises(HTTPException) as exc:
                next(db_session)

        self.assertEqual(exc.exception.status_code, 503)
        self.assertEqual(exc.exception.detail, "database_not_ready")


if __name__ == "__main__":
    unittest.main()
