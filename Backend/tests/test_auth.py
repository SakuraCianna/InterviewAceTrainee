from fastapi.testclient import TestClient

from app.main import app


def test_email_code_login_issues_token():
    client = TestClient(app)
    request_response = client.post("/api/auth/email-code/request", json={"email": "student@example.com"})

    assert request_response.status_code == 202
    code = request_response.json()["dev_code"]
    assert len(code) == 6

    login_response = client.post(
        "/api/auth/email-code/login",
        json={"email": "student@example.com", "code": code},
    )

    assert login_response.status_code == 200
    assert login_response.json()["token_type"] == "bearer"
    assert login_response.json()["access_token"].startswith("demo-token:")


def test_admin_login_requires_password_and_email_code():
    client = TestClient(app)
    register_response = client.post(
        "/api/auth/password/register",
        json={"email": "admin@example.com", "password": "StrongPass123"},
    )
    assert register_response.status_code == 201

    code_response = client.post("/api/auth/email-code/request", json={"email": "admin@example.com"})
    code = code_response.json()["dev_code"]

    admin_response = client.post(
        "/api/auth/admin/login",
        json={"email": "admin@example.com", "password": "StrongPass123", "code": code},
    )

    assert admin_response.status_code == 200
    assert admin_response.json()["access_token"].startswith("admin-demo-token:")

