import asyncio

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect, status

from app.core.security import ACCESS_TOKEN_COOKIE_NAME, decode_access_token
from app.services.auth_sessions import AuthSessionStore, get_auth_session_store
from app.services.user_credentials import UserCredentialStore, get_user_credential_store

router = APIRouter(prefix="/ws", tags=["websocket"])


@router.websocket("/interviews/{session_id}")
async def interview_socket(
    websocket: WebSocket,
    session_id: str,
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> None:
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
                payload = await asyncio.wait_for(websocket.receive_json(), timeout=5)
            except asyncio.TimeoutError:
                continue
            await websocket.send_json(
                {
                    "type": "event_ack",
                    "session_id": session_id,
                    "received_type": payload.get("type", "unknown"),
                }
            )
    except WebSocketDisconnect:
        return
