from fastapi.testclient import TestClient

from app.main import app


def test_health_endpoint_returns_ok():
    client = TestClient(app)
    response = client.get("/api/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "mianba-backend"}


def test_readiness_endpoint_reports_runtime_dependencies():
    client = TestClient(app)
    response = client.get("/api/health/readiness")

    assert response.status_code == 200
    data = response.json()
    assert data["service"] == "mianba-backend"
    assert data["status"] in {"ready", "degraded"}
    assert "database" in data["checks"]
    assert "redis" in data["checks"]
    assert "email" in data["checks"]
    assert "auth" in data["checks"]
