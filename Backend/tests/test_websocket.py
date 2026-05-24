from fastapi.testclient import TestClient
import pytest
from starlette.websockets import WebSocketDisconnect

from app.core.security import create_access_token
from app.main import app


def test_interview_websocket_sends_initial_session_state():
    client = TestClient(app)
    token = create_access_token("socket-user@example.com", "user")
    client.cookies.set("mianba_access_token", token)

    with client.websocket_connect("/api/ws/interviews/session-123") as websocket:
        payload = websocket.receive_json()

    assert payload["type"] == "session_state"
    assert payload["session_id"] == "session-123"
    assert payload["state"] == "connected"
    assert payload["role"] == "user"


def test_interview_websocket_echoes_client_events():
    client = TestClient(app)
    token = create_access_token("socket-user@example.com", "user")
    client.cookies.set("mianba_access_token", token)

    with client.websocket_connect("/api/ws/interviews/session-456") as websocket:
        websocket.receive_json()
        websocket.send_json({"type": "answer_started"})
        payload = websocket.receive_json()

    assert payload["type"] == "event_ack"
    assert payload["session_id"] == "session-456"
    assert payload["received_type"] == "answer_started"


def test_interview_websocket_accepts_session_cookie():
    client = TestClient(app)
    token = create_access_token("socket-cookie-user@example.com", "user")
    client.cookies.set("mianba_access_token", token)

    with client.websocket_connect("/api/ws/interviews/session-cookie") as websocket:
        payload = websocket.receive_json()

    assert payload["type"] == "session_state"
    assert payload["session_id"] == "session-cookie"


def test_interview_websocket_rejects_missing_token():
    client = TestClient(app)

    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/api/ws/interviews/session-789"):
            pass
