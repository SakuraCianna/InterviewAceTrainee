from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app


def allow_admin_email(email: str) -> None:
    settings = get_settings()
    allowed_emails = [item.strip() for item in settings.admin_email_allowlist.split(",") if item.strip()]
    allowed_emails.append(email)
    settings.admin_email_allowlist = ",".join(dict.fromkeys(allowed_emails))


def get_admin_token(client: TestClient, email: str | None = None) -> str:
    email = email or f"provider-admin-{uuid4().hex}@example.com"
    allow_admin_email(email)
    register_code = client.post("/api/auth/email-code/request", json={"email": email}).json()["dev_code"]
    client.post("/api/auth/password/register", json={"email": email, "password": "StrongPass123", "code": register_code})
    code = client.post("/api/auth/email-code/request", json={"email": email}).json()["dev_code"]
    return client.post(
        "/api/auth/admin/login",
        json={"email": email, "password": "StrongPass123", "code": code},
    ).json()["access_token"]


def test_create_provider_config_requires_admin_token():
    client = TestClient(app)

    response = client.post(
        "/api/ai-providers",
        json={
            "id": "deepseek-v4-test",
            "provider_type": "llm",
            "purpose": "general",
            "provider_name": "deepseek",
            "model_name": "deepseek-v4",
            "priority": 20,
            "region": "cn",
        },
    )

    assert response.status_code == 401


def test_list_provider_configs_requires_admin_token():
    client = TestClient(app)

    response = client.get("/api/ai-providers")

    assert response.status_code == 401


def test_default_provider_configs_include_deepseek_flash_fallback():
    client = TestClient(app)
    token = get_admin_token(client)

    response = client.get("/api/ai-providers", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    providers = response.json()
    deepseek = next(provider for provider in providers if provider["id"] == "deepseek-v4-flash")
    assert deepseek["provider_name"] == "deepseek"
    assert deepseek["model_name"] == "deepseek-v4-flash"
    assert deepseek["priority"] > next(provider for provider in providers if provider["id"] == "glm-4.7-flash")["priority"]


def test_default_provider_configs_prefer_domestic_free_quota_candidates_before_deepseek():
    client = TestClient(app)
    token = get_admin_token(client)

    response = client.get("/api/ai-providers", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    providers = {provider["id"]: provider for provider in response.json()}
    assert providers["glm-z1-flash"]["provider_name"] == "zhipu"
    assert providers["glm-4-flash-250414"]["provider_name"] == "zhipu"
    assert providers["qwen-flash"]["provider_name"] == "aliyun-bailian"
    assert providers["doubao-seed-1.6-flash"]["provider_name"] == "volcengine-ark"
    assert providers["glm-4.7-flash"]["priority"] < providers["qwen-flash"]["priority"]
    assert providers["glm-4.7-flash"]["priority"] < providers["glm-z1-flash"]["priority"]
    assert providers["glm-z1-flash"]["priority"] < providers["glm-4-flash-250414"]["priority"]
    assert providers["glm-4-flash-250414"]["priority"] < providers["qwen-flash"]["priority"]
    assert providers["qwen-flash"]["priority"] < providers["doubao-seed-1.6-flash"]["priority"]
    assert providers["doubao-seed-1.6-flash"]["priority"] < providers["deepseek-v4-flash"]["priority"]


def test_admin_can_create_and_update_provider_config():
    client = TestClient(app)
    token = get_admin_token(client)

    create_response = client.post(
        "/api/ai-providers",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "id": "deepseek-v4-test",
            "provider_type": "llm",
            "purpose": "general",
            "provider_name": "deepseek",
            "model_name": "deepseek-v4",
            "priority": 20,
            "region": "cn",
        },
    )

    assert create_response.status_code == 201
    assert create_response.json()["id"] == "deepseek-v4-test"
    assert create_response.json()["enabled"] is True

    update_response = client.put(
        "/api/ai-providers/deepseek-v4-test",
        headers={"Authorization": f"Bearer {token}"},
        json={"priority": 5, "enabled": False},
    )

    assert update_response.status_code == 200
    assert update_response.json()["priority"] == 5
    assert update_response.json()["enabled"] is False

    audit_response = client.get("/api/admin/audit-logs", headers={"Authorization": f"Bearer {token}"})
    assert audit_response.status_code == 200
    actions = [entry["action"] for entry in audit_response.json() if entry["target_id"] == "deepseek-v4-test"]
    assert "provider_create" in actions
    assert "provider_update" in actions
