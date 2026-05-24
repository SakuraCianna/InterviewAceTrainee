from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.config import get_settings
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


def get_admin_token(client: TestClient) -> str:
    email = unique_email("system-admin")
    allow_admin_email(email)
    register_code = request_dev_code(client, email)
    client.post("/api/auth/password/register", json={"email": email, "password": "StrongPass123", "code": register_code})
    login_code = request_dev_code(client, email)
    return client.post(
        "/api/auth/admin/login",
        json={"email": email, "password": "StrongPass123", "code": login_code},
    ).json()["access_token"]


def test_admin_can_update_system_config_and_disable_new_registration():
    client = TestClient(app)
    token = get_admin_token(client)

    list_response = client.get("/api/admin/system-configs", headers={"Authorization": f"Bearer {token}"})
    assert list_response.status_code == 200
    assert any(item["key"] == "registration_open" for item in list_response.json())

    update_response = client.put(
        "/api/admin/system-configs/registration_open",
        headers={"Authorization": f"Bearer {token}"},
        json={"value": False, "description": "temporarily closed"},
    )
    assert update_response.status_code == 200
    assert update_response.json()["value"] is False

    user_email = unique_email("closed-registration")
    register_code = request_dev_code(client, user_email)
    register_response = client.post(
        "/api/auth/password/register",
        json={"email": user_email, "password": "StrongPass123", "code": register_code},
    )
    assert register_response.status_code == 403
    assert register_response.json()["detail"] == "registration_closed"

    restore_response = client.put(
        "/api/admin/system-configs/registration_open",
        headers={"Authorization": f"Bearer {token}"},
        json={"value": True, "description": "registration reopened"},
    )
    assert restore_response.status_code == 200
