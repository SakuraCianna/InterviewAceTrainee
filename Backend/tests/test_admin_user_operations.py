from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.core.security import create_access_token
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


def grant_credits(client: TestClient, admin_token: str, user_email: str, amount: int) -> None:
    response = client.post(
        f"/api/admin/users/{user_email}/credits",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"change_amount": amount, "reason": "manual_grant"},
    )
    assert response.status_code == 200


def create_postgraduate_material(client: TestClient, user_token: str) -> str:
    response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {user_token}"},
        data={
            "interview_type": "postgraduate",
            "major": "计算机科学与技术",
            "research_direction": "智能教育",
        },
    )
    assert response.status_code == 201
    return response.json()["id"]


def test_admin_can_search_user_and_read_user_interview_history():
    client = TestClient(app)
    admin_token = create_access_token(unique_email("ops-admin"), "admin")
    user_email = unique_email("ops-user")
    user_token = create_access_token(user_email, "user")
    grant_credits(client, admin_token, user_email, 1)
    material_id = create_postgraduate_material(client, user_token)
    session_id = f"session-admin-user-ops-{uuid4().hex}"
    start_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {user_token}"},
        json={
            "session_id": session_id,
            "interview_type": "postgraduate",
            "material_id": material_id,
        },
    )
    assert start_response.status_code == 201

    search_response = client.get(
        "/api/admin/users/search",
        headers={"Authorization": f"Bearer {admin_token}"},
        params={"query": user_email},
    )
    assert search_response.status_code == 200
    search_results = search_response.json()
    assert search_results[0]["email"] == user_email
    assert search_results[0]["credit_balance"] == 0
    assert search_results[0]["total_interviews"] >= 1

    history_response = client.get(
        f"/api/admin/users/{user_email}/interviews",
        headers={"Authorization": f"Bearer {admin_token}"},
    )
    assert history_response.status_code == 200
    assert any(item["session_id"] == session_id for item in history_response.json())


def test_admin_can_disable_and_reenable_user_login():
    client = TestClient(app)
    admin_email = unique_email("status-admin")
    allow_admin_email(admin_email)
    admin_register_code = request_dev_code(client, admin_email)
    client.post(
        "/api/auth/password/register",
        json={"email": admin_email, "password": "StrongPass123", "code": admin_register_code},
    )
    admin_code = request_dev_code(client, admin_email)
    admin_token = client.post(
        "/api/auth/admin/login",
        json={"email": admin_email, "password": "StrongPass123", "code": admin_code},
    ).json()["access_token"]

    user_email = unique_email("disabled-user")
    register_code = request_dev_code(client, user_email)
    register_response = client.post(
        "/api/auth/password/register",
        json={"email": user_email, "password": "StrongPass123", "code": register_code},
    )
    assert register_response.status_code == 201

    disable_response = client.put(
        f"/api/admin/users/{user_email}/status",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"is_active": False, "reason": "refund_dispute_hold"},
    )
    assert disable_response.status_code == 200
    assert disable_response.json()["is_active"] is False

    password_login_response = client.post(
        "/api/auth/password/login",
        json={"email": user_email, "password": "StrongPass123"},
    )
    assert password_login_response.status_code == 403
    assert password_login_response.json()["detail"] == "user_disabled"

    search_response = client.get(
        "/api/admin/users/search",
        headers={"Authorization": f"Bearer {admin_token}"},
        params={"query": user_email},
    )
    assert search_response.status_code == 200
    assert search_response.json()[0]["is_active"] is False

    enable_response = client.put(
        f"/api/admin/users/{user_email}/status",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"is_active": True, "reason": "manual_restore"},
    )
    assert enable_response.status_code == 200
    assert enable_response.json()["is_active"] is True

    login_response = client.post(
        "/api/auth/password/login",
        json={"email": user_email, "password": "StrongPass123"},
    )
    assert login_response.status_code == 200
