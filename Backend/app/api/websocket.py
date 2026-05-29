import asyncio
import json

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect, status

from app.api.interviews import get_interview_store
from app.core.config import Settings, get_settings
from app.core.security import ACCESS_TOKEN_COOKIE_NAME, decode_access_token
from app.services.auth_sessions import AuthSessionStore, get_auth_session_store
from app.services.interview_runtime import DatabaseInterviewRuntimeStore, InMemoryInterviewRuntimeStore
from app.services.user_credentials import UserCredentialStore, get_user_credential_store

router = APIRouter(prefix="/ws", tags=["websocket"])
MAX_WEBSOCKET_MESSAGE_CHARS = 4096


@router.websocket("/interviews/{session_id}")
async def interview_socket(
    websocket: WebSocket,
    session_id: str,
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
    settings: Settings = Depends(get_settings),
) -> None:
    if not _is_allowed_origin(websocket.headers.get("origin"), settings):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    if not 1 <= len(session_id) <= 120:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    token = websocket.cookies.get(ACCESS_TOKEN_COOKIE_NAME)
    if not token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    try:
        claims = decode_access_token(token)
    except ValueError:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    if not auth_session_store.is_current_session(claims["sub"], claims["session_id"]) or not user_store.is_active(claims["sub"]):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    if interview_store.get_session(claims["sub"], session_id) is None:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    await websocket.send_json(
        {
            "type": "session_state",
            "session_id": session_id,
            "state": "connected",
            "role": claims["role"],
            "message": "interview websocket connected",
        }
    )

    try:
        while True:
            if not auth_session_store.is_current_session(claims["sub"], claims["session_id"]) or not user_store.is_active(claims["sub"]):
                await websocket.send_json(
                    {
                        "type": "session_kicked",
                        "session_id": session_id,
                        "reason": "account_logged_in_elsewhere",
                        "message": "account has logged in elsewhere",
                    }
                )
                await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
                return
            try:
                raw_payload = await asyncio.wait_for(websocket.receive_text(), timeout=5)
            except asyncio.TimeoutError:
                continue
            if len(raw_payload) > MAX_WEBSOCKET_MESSAGE_CHARS:
                await websocket.close(code=status.WS_1009_MESSAGE_TOO_BIG)
                return
            try:
                payload = json.loads(raw_payload)
            except json.JSONDecodeError:
                await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
                return
            if not isinstance(payload, dict):
                await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
                return
            await websocket.send_json(
                {
                    "type": "event_ack",
                    "session_id": session_id,
                    "received_type": payload.get("type", "unknown"),
                }
            )
    except WebSocketDisconnect:
        return


def _is_allowed_origin(origin: str | None, settings: Settings) -> bool:
    allowed_origins = {item.strip().rstrip("/") for item in settings.cors_origins.split(",") if item.strip()}
    if not origin:
        return not allowed_origins
    return origin.rstrip("/") in allowed_origins
