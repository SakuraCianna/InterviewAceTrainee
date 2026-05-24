from fastapi import APIRouter, WebSocket, WebSocketDisconnect, status

from app.core.security import ACCESS_TOKEN_COOKIE_NAME, decode_access_token

router = APIRouter(prefix="/ws", tags=["websocket"])


@router.websocket("/interviews/{session_id}")
async def interview_socket(websocket: WebSocket, session_id: str) -> None:
    token = websocket.cookies.get(ACCESS_TOKEN_COOKIE_NAME)
    if not token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    try:
        claims = decode_access_token(token)
    except ValueError:
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
            payload = await websocket.receive_json()
            await websocket.send_json(
                {
                    "type": "event_ack",
                    "session_id": session_id,
                    "received_type": payload.get("type", "unknown"),
                }
            )
    except WebSocketDisconnect:
        return
