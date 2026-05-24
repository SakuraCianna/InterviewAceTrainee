from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.security import create_access_token
from app.main import app


def unique_email(prefix: str) -> str:
    return f"{prefix}-{uuid4().hex}@example.com"


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
