from pathlib import PurePath

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status

from app.api.dependencies import TokenClaims, get_current_user_claims
from app.api.providers import get_provider_config_store
from app.core.config import Settings, get_settings
from app.schemas.interviews import InterviewType
from app.schemas.speech import SpeechRecognitionResponse, SpeechSynthesisRequest, SpeechSynthesisResponse
from app.services.ai_call_logs import AICallLogStore, get_ai_call_log_store
from app.services.ai_router import AIServiceRouter, AllProvidersFailedError, NoProviderAvailableError
from app.services.provider_configs import DatabaseProviderConfigStore, InMemoryProviderConfigStore
from app.services.tencent_speech import TencentSpeechClient

router = APIRouter(prefix="/speech", tags=["speech"])

ALLOWED_AUDIO_CONTENT_TYPES = {
    "audio/aac",
    "audio/flac",
    "audio/m4a",
    "audio/mp3",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
    "audio/wav",
    "audio/webm",
    "audio/x-m4a",
    "audio/x-wav",
    "video/webm",
}
ALLOWED_AUDIO_EXTENSIONS = {".aac", ".flac", ".m4a", ".mp3", ".ogg", ".wav", ".webm"}


@router.post("/asr", response_model=SpeechRecognitionResponse)
async def recognize_speech(
    audio_file: UploadFile = File(...),
    session_id: str = Form(default=""),
    interview_type: InterviewType | None = Form(default=None),
    _claims: TokenClaims = Depends(get_current_user_claims),
    provider_store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
    call_log_store: AICallLogStore = Depends(get_ai_call_log_store),
    settings: Settings = Depends(get_settings),
) -> SpeechRecognitionResponse:
    _validate_audio_upload(audio_file)
    audio_bytes = await _read_audio_upload_limited(audio_file, settings.speech_audio_max_upload_bytes)
    if not audio_bytes:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="audio_file_empty")

    router_service = AIServiceRouter(provider_store.list_configs())
    speech_client = TencentSpeechClient(settings)
    try:
        result = router_service.run_with_fallback(
            provider_type="asr",
            purpose="interview",
            operation=lambda config: speech_client.recognize(
                config,
                audio_bytes=audio_bytes,
                content_type=audio_file.content_type or "",
                filename=audio_file.filename,
                session_id=session_id,
                interview_type=interview_type,
            ),
        )
    except NoProviderAvailableError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="asr_provider_not_available") from exc
    except AllProvidersFailedError as exc:
        call_log_store.record_attempts(
            session_id=session_id or None,
            provider_type="asr",
            purpose="interview",
            attempts=exc.attempts,
        )
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail="asr_provider_failed") from exc

    call_log_store.record_attempts(
        session_id=session_id or None,
        provider_type="asr",
        purpose="interview",
        attempts=result.attempts,
    )
    return SpeechRecognitionResponse(text=result.value.text, provider_id=result.provider.id)


@router.post("/tts", response_model=SpeechSynthesisResponse)
def synthesize_speech(
    payload: SpeechSynthesisRequest,
    _claims: TokenClaims = Depends(get_current_user_claims),
    provider_store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
    call_log_store: AICallLogStore = Depends(get_ai_call_log_store),
    settings: Settings = Depends(get_settings),
) -> SpeechSynthesisResponse:
    router_service = AIServiceRouter(provider_store.list_configs())
    speech_client = TencentSpeechClient(settings)
    try:
        result = router_service.run_with_fallback(
            provider_type="tts",
            purpose="interview",
            operation=lambda config: speech_client.synthesize(
                config,
                text=payload.text,
                session_id=payload.session_id,
            ),
        )
    except NoProviderAvailableError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="tts_provider_not_available") from exc
    except AllProvidersFailedError as exc:
        call_log_store.record_attempts(
            session_id=payload.session_id,
            provider_type="tts",
            purpose="interview",
            attempts=exc.attempts,
        )
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail="tts_provider_failed") from exc

    call_log_store.record_attempts(
        session_id=payload.session_id,
        provider_type="tts",
        purpose="interview",
        attempts=result.attempts,
    )
    return SpeechSynthesisResponse(
        audio_base64=result.value.audio_base64,
        mime_type=result.value.mime_type,
        provider_id=result.provider.id,
    )


def _validate_audio_upload(audio_file: UploadFile) -> None:
    content_type = (audio_file.content_type or "").split(";")[0].strip().lower()
    extension = PurePath(audio_file.filename or "").suffix.lower()
    if content_type in ALLOWED_AUDIO_CONTENT_TYPES or extension in ALLOWED_AUDIO_EXTENSIONS:
        return
    raise HTTPException(status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="unsupported_audio_format")


async def _read_audio_upload_limited(audio_file: UploadFile, max_bytes: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await audio_file.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="audio_file_too_large")
        chunks.append(chunk)
    return b"".join(chunks)
