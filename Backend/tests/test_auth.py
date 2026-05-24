from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.core.security import decode_access_token
from app.main import app


def unique_email(prefix: str) -> str:
    return f"{prefix}-{uuid4().hex}@example.com"


def request_dev_code(client: TestClient, email: str) -> str:
    response = client.post("/api/auth/email-code/request", json={"email": email})

    assert response.status_code == 202
    return response.json()["dev_code"]


def allow_admin_email(email: str) -> None:
    settings = get_settings()
    allowed_emails = [item.strip() for item in settings.admin_email_allowlist.split(",") if item.strip()]
    allowed_emails.append(email)
    settings.admin_email_allowlist = ",".join(dict.fromkeys(allowed_emails))


def test_email_code_login_issues_token():
    client = TestClient(app)
    email = unique_email("student")
    code = request_dev_code(client, email)
    assert len(code) == 6

    login_response = client.post(
        "/api/auth/email-code/login",
        json={"email": email, "code": code},
    )

    assert login_response.status_code == 200
    assert login_response.json()["token_type"] == "bearer"
    claims = decode_access_token(login_response.json()["access_token"])
    assert claims["sub"] == email
    assert claims["role"] == "user"


def test_admin_login_requires_password_and_email_code():
    client = TestClient(app)
    email = unique_email("admin")
    allow_admin_email(email)
    register_code = request_dev_code(client, email)
    register_response = client.post(
        "/api/auth/password/register",
        json={"email": email, "password": "StrongPass123", "code": register_code},
    )
    assert register_response.status_code == 201

    code = request_dev_code(client, email)

    admin_response = client.post(
        "/api/auth/admin/login",
        json={"email": email, "password": "StrongPass123", "code": code},
    )

    assert admin_response.status_code == 200
    claims = decode_access_token(admin_response.json()["access_token"])
    assert claims["sub"] == email
    assert claims["role"] == "admin"


def test_regular_user_cannot_exchange_password_and_code_for_admin_token():
    client = TestClient(app)
    email = unique_email("regular-admin-attempt")
    register_code = request_dev_code(client, email)
    client.post(
        "/api/auth/password/register",
        json={"email": email, "password": "StrongPass123", "code": register_code},
    )
    code = request_dev_code(client, email)

    admin_response = client.post(
        "/api/auth/admin/login",
        json={"email": email, "password": "StrongPass123", "code": code},
    )

    assert admin_response.status_code == 403
    assert admin_response.json()["detail"] == "admin_email_not_allowed"


def test_password_register_issues_jwt_token():
    client = TestClient(app)
    email = unique_email("jwt-user")
    code = request_dev_code(client, email)
    response = client.post(
        "/api/auth/password/register",
        json={"email": email, "password": "StrongPass123", "code": code},
    )

    assert response.status_code == 201
    claims = decode_access_token(response.json()["access_token"])
    assert claims["sub"] == email
    assert claims["role"] == "user"


def test_me_endpoint_returns_current_token_claims():
    client = TestClient(app)
    email = unique_email("me-user")
    code = request_dev_code(client, email)
    response = client.post(
        "/api/auth/password/register",
        json={"email": email, "password": "StrongPass123", "code": code},
    )
    token = response.json()["access_token"]

    me_response = client.get("/api/auth/me", headers={"Authorization": f"Bearer {token}"})

    assert me_response.status_code == 200
    assert me_response.json()["email"] == email
    assert me_response.json()["role"] == "user"


def test_password_register_sets_http_only_session_cookie():
    client = TestClient(app)
    email = unique_email("cookie-user")
    code = request_dev_code(client, email)

    response = client.post(
        "/api/auth/password/register",
        json={"email": email, "password": "StrongPass123", "code": code},
    )

    assert response.status_code == 201
    assert "mianba_access_token=" in response.headers["set-cookie"]
    assert "HttpOnly" in response.headers["set-cookie"]
    me_response = client.get("/api/auth/me")
    assert me_response.status_code == 200
    assert me_response.json()["email"] == email


def test_admin_credit_adjustment_requires_admin_bearer_token():
    client = TestClient(app)
    response = client.post(
        "/api/admin/users/user-1/credits",
        json={"current_balance": 0, "change_amount": 3, "reason": "manual_grant"},
    )

    assert response.status_code == 401


def test_admin_credit_adjustment_accepts_admin_bearer_token():
    client = TestClient(app)
    user_id = f"credit-user-{uuid4().hex}@example.com"
    admin_email = unique_email("credit-admin")
    allow_admin_email(admin_email)
    register_code = request_dev_code(client, admin_email)
    client.post(
        "/api/auth/password/register",
        json={"email": admin_email, "password": "StrongPass123", "code": register_code},
    )
    admin_code = request_dev_code(client, admin_email)
    admin_response = client.post(
        "/api/auth/admin/login",
        json={
            "email": admin_email,
            "password": "StrongPass123",
            "code": admin_code,
        },
    )
    token = admin_response.json()["access_token"]

    response = client.post(
        f"/api/admin/users/{user_id}/credits",
        headers={"Authorization": f"Bearer {token}"},
        json={"current_balance": 0, "change_amount": 3, "reason": "manual_grant"},
    )

    assert response.status_code == 200
    assert response.json()["balance_after"] == 3
    assert response.json()["operator_admin_id"] == admin_email

    audit_response = client.get("/api/admin/audit-logs", headers={"Authorization": f"Bearer {token}"})
    assert audit_response.status_code == 200
    assert any(entry["action"] == "credit_adjust" and entry["target_id"] == user_id for entry in audit_response.json())

    ledger_response = client.get(f"/api/admin/users/{user_id}/credit-ledger", headers={"Authorization": f"Bearer {token}"})
    assert ledger_response.status_code == 200
    assert any(entry["reason"] == "manual_grant" and entry["change_amount"] == 3 for entry in ledger_response.json())

    ai_logs_response = client.get("/api/admin/ai-call-logs", headers={"Authorization": f"Bearer {token}"})
    assert ai_logs_response.status_code == 200
