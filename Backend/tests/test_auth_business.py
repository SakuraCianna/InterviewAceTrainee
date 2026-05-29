import unittest

from fastapi import HTTPException, Response
from starlette.requests import Request

from app.api.auth import login_with_email_code, login_with_password, register_with_password
from app.core.config import Settings
from app.core.security import hash_password
from app.schemas.auth import EmailCodeLoginRequest, PasswordLoginRequest, PasswordRegisterRequest
from app.services.auth_login_logs import InMemoryAuthLoginLogStore
from app.services.auth_sessions import InMemoryAuthSessionStore, _memory_auth_sessions
from app.services.email_codes import EmailCodeStore
from app.services.interview_vouchers import InMemoryInterviewVoucherStore
from app.services.system_configs import InMemorySystemConfigStore
from app.services.user_credentials import InMemoryUserCredentialStore, UserAccountRecord, _memory_users


def build_request() -> Request:
    return Request(
        {
            "type": "http",
            "method": "POST",
            "path": "/api/auth/test",
            "headers": [],
            "client": ("127.0.0.1", 12345),
        }
    )


class AuthBusinessTests(unittest.TestCase):
    def setUp(self) -> None:
        _memory_users.clear()
        _memory_auth_sessions.clear()
        self.settings = Settings(access_token_secret="x" * 40)

    def tearDown(self) -> None:
        _memory_users.clear()
        _memory_auth_sessions.clear()

    def test_email_code_login_creates_new_user_trial_voucher(self) -> None:
        email = "new-user@example.com"
        code_store = EmailCodeStore()
        code = code_store.issue_code(email).code
        user_store = InMemoryUserCredentialStore()
        voucher_store = InMemoryInterviewVoucherStore()

        login_with_email_code(
            request=build_request(),
            payload=EmailCodeLoginRequest(email=email, code=code),
            response=Response(),
            settings=self.settings,
            code_store=code_store,
            user_store=user_store,
            system_config_store=InMemorySystemConfigStore(),
            voucher_store=voucher_store,
            login_log_store=InMemoryAuthLoginLogStore(),
            auth_session_store=InMemoryAuthSessionStore(),
        )

        self.assertTrue(user_store.user_exists(email))
        self.assertEqual(voucher_store.available_count(email), 1)

    def test_user_password_login_rejects_admin_account(self) -> None:
        email = "admin@example.com"
        _memory_users[email] = UserAccountRecord(
            email=email,
            role="admin",
            password_hash=hash_password("secret123"),
        )

        with self.assertRaises(HTTPException) as exc:
            login_with_password(
                request=build_request(),
                payload=PasswordLoginRequest(email=email, password="secret123"),
                response=Response(),
                settings=self.settings,
                user_store=InMemoryUserCredentialStore(),
                system_config_store=InMemorySystemConfigStore(),
                login_log_store=InMemoryAuthLoginLogStore(),
                auth_session_store=InMemoryAuthSessionStore(),
            )

        self.assertEqual(exc.exception.status_code, 403)
        self.assertEqual(exc.exception.detail, "admin_login_required")

    def test_user_password_register_rejects_admin_account(self) -> None:
        email = "admin-register@example.com"
        _memory_users[email] = UserAccountRecord(email=email, role="admin")
        code_store = EmailCodeStore()
        code = code_store.issue_code(email).code
        voucher_store = InMemoryInterviewVoucherStore()

        with self.assertRaises(HTTPException) as exc:
            register_with_password(
                request=build_request(),
                payload=PasswordRegisterRequest(email=email, password="secret123", code=code),
                response=Response(),
                settings=self.settings,
                code_store=code_store,
                user_store=InMemoryUserCredentialStore(),
                system_config_store=InMemorySystemConfigStore(),
                voucher_store=voucher_store,
                login_log_store=InMemoryAuthLoginLogStore(),
                auth_session_store=InMemoryAuthSessionStore(),
            )

        self.assertEqual(exc.exception.status_code, 403)
        self.assertEqual(exc.exception.detail, "admin_login_required")
        self.assertEqual(voucher_store.available_count(email), 0)

    def test_user_email_code_login_rejects_admin_account(self) -> None:
        email = "admin-code@example.com"
        _memory_users[email] = UserAccountRecord(email=email, role="admin")
        code_store = EmailCodeStore()
        code = code_store.issue_code(email).code
        voucher_store = InMemoryInterviewVoucherStore()

        with self.assertRaises(HTTPException) as exc:
            login_with_email_code(
                request=build_request(),
                payload=EmailCodeLoginRequest(email=email, code=code),
                response=Response(),
                settings=self.settings,
                code_store=code_store,
                user_store=InMemoryUserCredentialStore(),
                system_config_store=InMemorySystemConfigStore(),
                voucher_store=voucher_store,
                login_log_store=InMemoryAuthLoginLogStore(),
                auth_session_store=InMemoryAuthSessionStore(),
            )

        self.assertEqual(exc.exception.status_code, 403)
        self.assertEqual(exc.exception.detail, "admin_login_required")
        self.assertEqual(voucher_store.available_count(email), 0)


if __name__ == "__main__":
    unittest.main()
