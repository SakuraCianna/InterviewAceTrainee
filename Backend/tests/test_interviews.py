from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.security import create_access_token
from app.main import app


def unique_email(prefix: str) -> str:
    return f"{prefix}-{uuid4().hex}@example.com"


def grant_credits(client: TestClient, user_email: str, amount: int) -> int:
    admin_token = create_access_token(unique_email("grant-admin"), "admin")
    response = client.post(
        f"/api/admin/users/{user_email}/credits",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"change_amount": amount, "reason": "manual_grant"},
    )

    assert response.status_code == 200
    return response.json()["balance_after"]


def create_material(client: TestClient, token: str, interview_type: str = "job") -> str:
    if interview_type == "job":
        response = client.post(
            "/api/interview-materials",
            headers={"Authorization": f"Bearer {token}"},
            data={
                "interview_type": "job",
                "job_title": "后端开发实习生",
                "job_requirements": "需要熟悉 FastAPI、PostgreSQL、Redis 和接口稳定性。",
            },
            files={"resume_file": ("resume.txt", "我负责 FastAPI 项目、Redis 缓存和 PostgreSQL 报告。", "text/plain")},
        )
    else:
        response = client.post(
            "/api/interview-materials",
            headers={"Authorization": f"Bearer {token}"},
            data={
                "interview_type": "postgraduate",
                "major": "计算机科学与技术",
                "research_direction": "智能教育",
            },
        )
    assert response.status_code == 201
    return response.json()["id"]


def test_interview_start_requires_login_token():
    client = TestClient(app)

    response = client.post("/api/interviews", json={"session_id": "session-no-token", "interview_type": "job"})

    assert response.status_code == 401


def test_job_interview_consumes_three_server_side_credits_and_ignores_client_spoofing():
    client = TestClient(app)
    user_email = unique_email("job-user")
    grant_credits(client, user_email, 5)
    token = create_access_token(user_email, "user")
    material_id = create_material(client, token, "job")

    response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "session_id": "session-job-1",
            "current_credit_balance": 999,
            "is_admin": True,
            "interview_type": "job",
            "material_id": material_id,
        },
    )

    assert response.status_code == 201
    data = response.json()
    assert data["credit_change"] == -3
    assert data["balance_after"] == 2
    assert data["ledger_reason"] == "interview_start:job"
    assert data["status"] == "in_progress"
    assert data["current_question"]["text"]
    assert data["current_step_index"] == 0
    assert data["total_steps"] >= 3


def test_starting_existing_incomplete_session_resumes_without_charging_again():
    client = TestClient(app)
    user_email = unique_email("resume-user")
    grant_credits(client, user_email, 3)
    token = create_access_token(user_email, "user")
    material_id = create_material(client, token, "job")

    first_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": "session-resume-no-double-charge", "interview_type": "job", "material_id": material_id},
    )
    second_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": "session-resume-no-double-charge", "interview_type": "job", "material_id": material_id},
    )

    assert first_response.status_code == 201
    assert second_response.status_code == 201
    assert second_response.json()["credit_change"] == 0
    assert second_response.json()["balance_after"] == 0
    assert second_response.json()["current_question"]["text"] == first_response.json()["current_question"]["text"]


def test_ielts_interview_consumes_two_credits():
    client = TestClient(app)
    user_email = unique_email("ielts-user")
    grant_credits(client, user_email, 3)
    token = create_access_token(user_email, "user")

    response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "session_id": "session-ielts-1",
            "interview_type": "ielts",
        },
    )

    assert response.status_code == 201
    assert response.json()["credit_change"] == -2
    assert response.json()["balance_after"] == 1


def test_interview_start_rejects_when_balance_is_below_product_cost():
    client = TestClient(app)
    user_email = unique_email("low-balance-user")
    grant_credits(client, user_email, 2)
    token = create_access_token(user_email, "user")
    material_id = create_material(client, token, "job")

    response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "session_id": "session-job-low-balance",
            "interview_type": "job",
            "material_id": material_id,
        },
    )

    assert response.status_code == 402
    assert response.json()["detail"] == "insufficient_credits"


def test_admin_interview_keeps_unlimited_usage():
    client = TestClient(app)
    admin_token = create_access_token(unique_email("interview-admin"), "admin")
    material_id = create_material(client, admin_token, "job")

    response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={
            "session_id": "session-admin-1",
            "interview_type": "job",
            "material_id": material_id,
        },
    )

    assert response.status_code == 201
    assert response.json()["credit_change"] == 0
    assert response.json()["balance_after"] == 0
    assert response.json()["ledger_reason"] == "admin_unlimited_interview"


def test_answering_all_questions_completes_session_and_returns_report():
    client = TestClient(app)
    user_email = unique_email("report-user")
    grant_credits(client, user_email, 1)
    token = create_access_token(user_email, "user")
    session_id = "session-report-postgraduate"
    material_id = create_material(client, token, "postgraduate")

    start_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": session_id, "interview_type": "postgraduate", "material_id": material_id},
    )
    assert start_response.status_code == 201

    payload = start_response.json()
    seen_questions = [payload["current_question"]["text"]]
    for index in range(10):
        answer_response = client.post(
            f"/api/interviews/{session_id}/answers",
            headers={"Authorization": f"Bearer {token}"},
            json={"answer_text": f"这是第 {index + 1} 轮回答，包含背景、行动和结果。"},
        )
        assert answer_response.status_code == 200
        payload = answer_response.json()
        if payload["status"] == "completed":
            break
        seen_questions.append(payload["current_question"]["text"])

    assert payload["status"] == "completed"
    assert payload["report"]["total_score"] >= 60
    assert len(payload["report"]["dimensions"]) >= 4
    assert len(payload["report"]["turns"]) == len(seen_questions)

    report_response = client.get(f"/api/interviews/{session_id}/report", headers={"Authorization": f"Bearer {token}"})
    assert report_response.status_code == 200
    assert report_response.json()["session_id"] == session_id


def test_completed_session_id_cannot_be_reused_and_double_charged():
    client = TestClient(app)
    user_email = unique_email("completed-reuse-user")
    grant_credits(client, user_email, 3)
    token = create_access_token(user_email, "user")
    session_id = "session-completed-reuse-postgraduate"
    material_id = create_material(client, token, "postgraduate")

    start_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": session_id, "interview_type": "postgraduate", "material_id": material_id},
    )
    assert start_response.status_code == 201

    payload = start_response.json()
    while payload["status"] != "completed":
        answer_response = client.post(
            f"/api/interviews/{session_id}/answers",
            headers={"Authorization": f"Bearer {token}"},
            json={"answer_text": "我会按结论、原因、例子和复盘四步回答，并补充下一步改进。"},
        )
        assert answer_response.status_code == 200
        payload = answer_response.json()

    reuse_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": session_id, "interview_type": "postgraduate", "material_id": material_id},
    )

    assert reuse_response.status_code == 409
    assert reuse_response.json()["detail"] == "interview_session_already_completed"


def test_active_session_endpoint_returns_latest_interrupted_session():
    client = TestClient(app)
    user_email = unique_email("active-user")
    grant_credits(client, user_email, 1)
    token = create_access_token(user_email, "user")

    start_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": "session-active-civil", "interview_type": "civil_service"},
    )
    active_response = client.get("/api/interviews/active", headers={"Authorization": f"Bearer {token}"})

    assert start_response.status_code == 201
    assert active_response.status_code == 200
    assert active_response.json()["session_id"] == "session-active-civil"
    assert active_response.json()["current_question"]["text"] == start_response.json()["current_question"]["text"]


def test_user_can_read_own_interview_history_without_leaking_other_users_sessions():
    client = TestClient(app)
    user_email = unique_email("history-user")
    other_email = unique_email("history-other")
    grant_credits(client, user_email, 1)
    token = create_access_token(user_email, "user")
    other_token = create_access_token(other_email, "user")
    session_id = f"session-history-{uuid4().hex}"
    material_id = create_material(client, token, "postgraduate")

    start_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": session_id, "interview_type": "postgraduate", "material_id": material_id},
    )
    assert start_response.status_code == 201

    payload = start_response.json()
    while payload["status"] != "completed":
        answer_response = client.post(
            f"/api/interviews/{session_id}/answers",
            headers={"Authorization": f"Bearer {token}"},
            json={"answer_text": "I can explain my background, motivation, examples and next improvement steps clearly."},
        )
        assert answer_response.status_code == 200
        payload = answer_response.json()

    history_response = client.get("/api/interviews/history", headers={"Authorization": f"Bearer {token}"})
    other_history_response = client.get("/api/interviews/history", headers={"Authorization": f"Bearer {other_token}"})

    assert history_response.status_code == 200
    history_item = next(item for item in history_response.json() if item["session_id"] == session_id)
    assert history_item["interview_type"] == "postgraduate"
    assert history_item["status"] == "completed"
    assert history_item["current_step_index"] == payload["current_step_index"]
    assert history_item["total_steps"] == payload["total_steps"]
    assert history_item["report_total_score"] == payload["report"]["total_score"]
    assert history_item["created_at"]

    assert other_history_response.status_code == 200
    assert all(item["session_id"] != session_id for item in other_history_response.json())
