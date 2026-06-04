import asyncio
import json
from json import JSONDecodeError
from time import perf_counter
from typing import Any
from uuid import uuid4

import websockets
from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect, status
from websockets.exceptions import ConnectionClosed

from app.api.interviews import get_interview_store
from app.api.providers import get_provider_config_store
from app.core.config import Settings, get_settings
from app.core.security import ACCESS_TOKEN_COOKIE_NAME, decode_access_token
from app.schemas.interviews import InterviewType
from app.services.ai_call_logs import AICallLogStore, get_ai_call_log_store
from app.services.ai_router import AIProviderAttempt, AIServiceRouter, NoProviderAvailableError
from app.services.auth_sessions import AuthSessionStore, get_auth_session_store
from app.services.capacity_gate import acquire_capacity
from app.services.interview_runtime import DatabaseInterviewRuntimeStore, InMemoryInterviewRuntimeStore
from app.services.provider_configs import DatabaseProviderConfigStore, InMemoryProviderConfigStore
from app.services.tencent_speech import SpeechProviderError, TencentSpeechClient
from app.services.user_credentials import UserCredentialStore, get_user_credential_store

router = APIRouter(prefix="/ws/speech", tags=["speech-realtime"])

REALTIME_PCM_BYTES_PER_SECOND = 16000 * 2
MAX_CLIENT_TEXT_CHARS = 2048
MAX_AUDIO_CHUNK_BYTES = 64 * 1024
TENCENT_FINAL_TIMEOUT_SECONDS = 12
WS_TRY_AGAIN_LATER = 1013


@router.websocket("/asr/{session_id}")
async def realtime_asr_socket(
    websocket: WebSocket,
    session_id: str,
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
    provider_store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
    call_log_store: AICallLogStore = Depends(get_ai_call_log_store),
    settings: Settings = Depends(get_settings),
) -> None:
    if not _is_allowed_origin(websocket.headers.get("origin"), settings):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    if not 1 <= len(session_id) <= 120:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    claims = _claims_from_socket(websocket, auth_session_store, user_store)
    if claims is None:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    state = interview_store.get_session(claims["sub"], session_id)
    if state is None or state.status == "completed":
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    capacity_lease = acquire_capacity(
        "asr:realtime",
        settings.realtime_asr_concurrency_limit,
        settings.realtime_asr_capacity_lease_seconds,
    )
    if not capacity_lease.acquired:
        await websocket.accept()
        await websocket.send_json(
            {
                "type": "asr_error",
                "detail": "asr_capacity_full",
                "retry_after_seconds": capacity_lease.retry_after_seconds,
            }
        )
        await _close_safely(websocket, code=WS_TRY_AGAIN_LATER)
        return

    await websocket.accept()
    start_payload = await _receive_start_payload(websocket)
    if start_payload is None:
        capacity_lease.release()
        await _send_error_and_close(websocket, "request_validation_failed")
        return

    interview_type = _resolve_interview_type(start_payload, state.interview_type)
    try:
        provider = AIServiceRouter(provider_store.list_configs()).select_provider("asr", "interview")
    except NoProviderAvailableError:
        capacity_lease.release()
        await _send_error_and_close(websocket, "asr_provider_not_available")
        return

    speech_client = TencentSpeechClient(settings)
    voice_id = str(uuid4())
    try:
        tencent_url = speech_client.build_realtime_asr_url(provider, voice_id=voice_id, interview_type=interview_type)
    except SpeechProviderError as exc:
        capacity_lease.release()
        await _send_error_and_close(websocket, str(exc) or "asr_provider_not_available")
        return

    started_at = perf_counter()
    transcript = RealtimeTranscript()
    audio_bytes = 0
    error_message = ""
    max_audio_bytes = max(REALTIME_PCM_BYTES_PER_SECOND, settings.tencent_realtime_asr_max_seconds * REALTIME_PCM_BYTES_PER_SECOND)

    try:
        async with websockets.connect(tencent_url, max_size=2 * 1024 * 1024, open_timeout=8, close_timeout=3) as tencent_ws:
            await websocket.send_json(
                {
                    "type": "asr_ready",
                    "session_id": session_id,
                    "sample_rate": 16000,
                    "chunk_ms": 200,
                    "message": "realtime asr connected",
                }
            )

            async def consume_client() -> str:
                nonlocal audio_bytes, error_message
                while True:
                    message = await websocket.receive()
                    message_type = message.get("type")
                    if message_type == "websocket.disconnect":
                        return "client_disconnected"
                    text = message.get("text")
                    if text is not None:
                        if len(text) > MAX_CLIENT_TEXT_CHARS:
                            error_message = "client_control_message_too_large"
                            await websocket.close(code=status.WS_1009_MESSAGE_TOO_BIG)
                            return "client_error"
                        payload = _parse_client_payload(text)
                        if payload is None:
                            error_message = "client_control_message_invalid"
                            await _send_error_and_close(websocket, "request_validation_failed")
                            return "client_error"
                        if payload.get("type") == "end":
                            await tencent_ws.send(json.dumps({"type": "end"}, ensure_ascii=False))
                            return "client_end"
                        continue

                    chunk = message.get("bytes")
                    if not chunk:
                        continue
                    if len(chunk) > MAX_AUDIO_CHUNK_BYTES:
                        error_message = "client_audio_chunk_too_large"
                        await websocket.close(code=status.WS_1009_MESSAGE_TOO_BIG)
                        return "client_error"
                    audio_bytes += len(chunk)
                    if audio_bytes > max_audio_bytes:
                        error_message = "audio_duration_too_long"
                        await websocket.send_json({"type": "asr_error", "detail": "audio_duration_too_long"})
                        await tencent_ws.send(json.dumps({"type": "end"}, ensure_ascii=False))
                        return "client_end"
                    await tencent_ws.send(chunk)

            async def consume_tencent() -> str:
                nonlocal error_message
                async for raw_payload in tencent_ws:
                    payload = _parse_tencent_payload(raw_payload)
                    if payload is None:
                        continue
                    provider_code = int(payload.get("code") or 0)
                    if provider_code != 0:
                        error_message = str(payload.get("message") or f"tencent_asr_error_{provider_code}")
                        await websocket.send_json(
                            {
                                "type": "asr_error",
                                "detail": "asr_provider_failed",
                                "provider_code": provider_code,
                                "message": payload.get("message") or "",
                            }
                        )
                        return "provider_error"

                    result = payload.get("result") if isinstance(payload.get("result"), dict) else {}
                    event = transcript.update(result)
                    if event is not None:
                        await websocket.send_json(event)

                    if int(payload.get("final") or 0) == 1:
                        await websocket.send_json({"type": "asr_completed", "text": transcript.text})
                        return "provider_final"
                return "provider_closed"

            client_task = asyncio.create_task(consume_client())
            provider_task = asyncio.create_task(consume_tencent())
            done, pending = await asyncio.wait({client_task, provider_task}, return_when=asyncio.FIRST_COMPLETED)

            if client_task in done:
                client_result = client_task.result()
                if client_result == "client_end" and not provider_task.done():
                    try:
                        await asyncio.wait_for(provider_task, timeout=TENCENT_FINAL_TIMEOUT_SECONDS)
                    except asyncio.TimeoutError:
                        await websocket.send_json({"type": "asr_completed", "text": transcript.text})
                        provider_task.cancel()
                elif not provider_task.done():
                    provider_task.cancel()
            elif provider_task in done and not client_task.done():
                client_task.cancel()

            for task in pending:
                if not task.done():
                    task.cancel()
    except (ConnectionClosed, OSError, TimeoutError, WebSocketDisconnect) as exc:
        error_message = error_message or str(exc) or "asr_provider_failed"
        if not isinstance(exc, WebSocketDisconnect):
            await _send_json_safely(websocket, {"type": "asr_error", "detail": "asr_provider_failed"})
    finally:
        capacity_lease.release()
        latency_ms = int((perf_counter() - started_at) * 1000)
        call_log_store.record_attempts(
            session_id=session_id,
            provider_type="asr",
            purpose="interview",
            attempts=[
                AIProviderAttempt(
                    provider_id=provider.id,
                    provider_name=provider.provider_name,
                    model_name=provider.model_name,
                    success=not error_message,
                    error_message=error_message,
                    latency_ms=latency_ms,
                    provider_request_id=voice_id,
                    audio_duration_ms=int(audio_bytes / REALTIME_PCM_BYTES_PER_SECOND * 1000) if audio_bytes else None,
                    characters=len(transcript.text),
                    usage_json={"mode": "realtime_websocket", "audio_bytes": audio_bytes},
                )
            ],
        )
        await _close_safely(websocket)


class RealtimeTranscript:
    def __init__(self) -> None:
        self._stable_segments: dict[int, str] = {}
        self._partial_text = ""

    @property
    def text(self) -> str:
        stable = [self._stable_segments[index] for index in sorted(self._stable_segments)]
        parts = [*stable, self._partial_text]
        return " ".join(part.strip() for part in parts if part and part.strip()).strip()

    def update(self, result: dict[str, Any]) -> dict[str, Any] | None:
        text = str(result.get("voice_text_str") or "").strip()
        if not text:
            return None
        slice_type = int(result.get("slice_type") or -1)
        index = int(result.get("index") or 0)
        if slice_type == 2:
            self._stable_segments[index] = text
            self._partial_text = ""
        elif slice_type == 1:
            self._partial_text = text
        elif slice_type == 0:
            self._partial_text = text
        else:
            self._partial_text = text
        return {
            "type": "asr_result",
            "text": self.text,
            "current_text": text,
            "slice_type": slice_type,
            "index": index,
            "is_stable": slice_type == 2,
        }


def _claims_from_socket(
    websocket: WebSocket,
    auth_session_store: AuthSessionStore,
    user_store: UserCredentialStore,
) -> dict[str, str] | None:
    token = websocket.cookies.get(ACCESS_TOKEN_COOKIE_NAME)
    if not token:
        return None
    try:
        claims = decode_access_token(token)
    except ValueError:
        return None
    if not auth_session_store.is_current_session(claims["sub"], claims["session_id"]):
        return None
    user_record = user_store.get_user_record(claims["sub"])
    if user_record is None or not user_record.is_active:
        return None
    return {"sub": claims["sub"], "role": user_record.role, "session_id": claims["session_id"]}


async def _receive_start_payload(websocket: WebSocket) -> dict[str, Any] | None:
    try:
        raw_payload = await asyncio.wait_for(websocket.receive_text(), timeout=10)
    except (asyncio.TimeoutError, WebSocketDisconnect):
        return None
    payload = _parse_client_payload(raw_payload)
    if payload is None or payload.get("type") != "start":
        return None
    return payload


def _parse_client_payload(raw_payload: str) -> dict[str, Any] | None:
    try:
        payload = json.loads(raw_payload)
    except JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def _parse_tencent_payload(raw_payload: str | bytes) -> dict[str, Any] | None:
    if isinstance(raw_payload, bytes):
        raw_payload = raw_payload.decode("utf-8", errors="ignore")
    try:
        payload = json.loads(raw_payload)
    except JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def _resolve_interview_type(payload: dict[str, Any], fallback: InterviewType) -> InterviewType:
    value = payload.get("interview_type")
    try:
        return InterviewType(str(value))
    except ValueError:
        return fallback


def _is_allowed_origin(origin: str | None, settings: Settings) -> bool:
    allowed_origins = {item.strip().rstrip("/") for item in settings.cors_origins.split(",") if item.strip()}
    if "*" in allowed_origins:
        return True
    if not origin:
        return not allowed_origins
    return origin.rstrip("/") in allowed_origins


async def _send_error_and_close(websocket: WebSocket, detail: str) -> None:
    await _send_json_safely(websocket, {"type": "asr_error", "detail": detail})
    await _close_safely(websocket, code=status.WS_1008_POLICY_VIOLATION)


async def _send_json_safely(websocket: WebSocket, payload: dict[str, Any]) -> None:
    try:
        await websocket.send_json(payload)
    except RuntimeError:
        return


async def _close_safely(websocket: WebSocket, code: int = status.WS_1000_NORMAL_CLOSURE) -> None:
    try:
        await websocket.close(code=code)
    except RuntimeError:
        return
