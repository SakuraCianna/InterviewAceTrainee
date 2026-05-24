from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.security import create_access_token
from app.main import app


def unique_email(prefix: str) -> str:
    return f"{prefix}-{uuid4().hex}@example.com"


def grant_credits(client: TestClient, user_email: str, amount: int) -> None:
    admin_token = create_access_token(unique_email("material-admin"), "admin")
    response = client.post(
        f"/api/admin/users/{user_email}/credits",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"change_amount": amount, "reason": "manual_grant"},
    )

    assert response.status_code == 200


def test_job_material_upload_extracts_resume_and_requires_job_context():
    client = TestClient(app)
    user_email = unique_email("material-job")
    token = create_access_token(user_email, "user")

    response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "interview_type": "job",
            "job_title": "后端开发实习生",
            "job_requirements": "熟悉 FastAPI、PostgreSQL、Redis，能解释项目取舍和线上排障。",
        },
        files={
            "resume_file": (
                "resume.txt",
                "姓名：测试用户\n项目：AI 面试训练平台，使用 FastAPI、PostgreSQL、Redis 和 Docker Compose。",
                "text/plain",
            )
        },
    )

    assert response.status_code == 201
    data = response.json()
    assert data["id"]
    assert data["interview_type"] == "job"
    assert data["job_title"] == "后端开发实习生"
    assert data["extracted_text_chars"] > 20
    assert "FastAPI" in data["resume_text_preview"]
    assert "resume_file_bytes" not in data


def test_resume_upload_rejects_oversized_payload_before_material_is_created():
    client = TestClient(app)
    user_email = unique_email("material-large")
    token = create_access_token(user_email, "user")

    response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "interview_type": "job",
            "job_title": "后端开发实习生",
            "job_requirements": "需要熟悉 FastAPI。",
        },
        files={"resume_file": ("resume.txt", b"x" * (5 * 1024 * 1024 + 1), "text/plain")},
    )

    assert response.status_code == 413
    assert response.json()["detail"] == "resume_file_too_large"


def test_resume_upload_rejects_double_extension_and_sanitizes_stored_filename():
    client = TestClient(app)
    user_email = unique_email("material-name")
    token = create_access_token(user_email, "user")

    rejected_response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "interview_type": "job",
            "job_title": "后端开发实习生",
            "job_requirements": "需要熟悉 FastAPI。",
        },
        files={"resume_file": ("resume.pdf.exe", b"malicious", "application/octet-stream")},
    )
    assert rejected_response.status_code == 422
    assert rejected_response.json()["detail"] == "unsupported_resume_format"

    accepted_response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "interview_type": "job",
            "job_title": "后端开发实习生",
            "job_requirements": "需要熟悉 FastAPI。",
        },
        files={
            "resume_file": (
                "../unsafe name 简历.txt",
                "项目：FastAPI 面试平台。",
                "text/plain",
            )
        },
    )

    assert accepted_response.status_code == 201
    assert accepted_response.json()["resume_filename"] == "unsafe_name_简历.txt"


def test_job_interview_requires_prepared_material_and_uses_it_in_first_question():
    client = TestClient(app)
    user_email = unique_email("material-start-job")
    grant_credits(client, user_email, 3)
    token = create_access_token(user_email, "user")

    missing_material_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={"session_id": "session-missing-material", "interview_type": "job"},
    )
    assert missing_material_response.status_code == 400
    assert missing_material_response.json()["detail"] == "interview_material_required"

    material_response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "interview_type": "job",
            "job_title": "AI 后端工程师",
            "job_requirements": "需要熟悉 Redis 缓存、模型路由、接口稳定性和日志追踪。",
        },
        files={
            "resume_file": (
                "resume.txt",
                "我做过面试练习平台，负责模型路由、Redis 次数扣减和 PostgreSQL 复盘报告。",
                "text/plain",
            )
        },
    )
    assert material_response.status_code == 201

    start_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "session_id": "session-job-with-material",
            "interview_type": "job",
            "material_id": material_response.json()["id"],
        },
    )

    assert start_response.status_code == 201
    question = start_response.json()["current_question"]["text"]
    assert "AI 后端工程师" in question
    assert "Redis" in question or "模型路由" in question


def test_postgraduate_material_requires_major_and_personalizes_first_question():
    client = TestClient(app)
    user_email = unique_email("material-postgraduate")
    grant_credits(client, user_email, 1)
    token = create_access_token(user_email, "user")

    invalid_response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {token}"},
        data={"interview_type": "postgraduate"},
    )
    assert invalid_response.status_code == 422

    material_response = client.post(
        "/api/interview-materials",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "interview_type": "postgraduate",
            "major": "计算机科学与技术",
            "research_direction": "自然语言处理与智能教育",
        },
    )
    assert material_response.status_code == 201

    start_response = client.post(
        "/api/interviews",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "session_id": "session-postgraduate-with-material",
            "interview_type": "postgraduate",
            "material_id": material_response.json()["id"],
        },
    )

    assert start_response.status_code == 201
    question = start_response.json()["current_question"]["text"]
    assert "计算机科学与技术" in question
    assert "自然语言处理" in question
