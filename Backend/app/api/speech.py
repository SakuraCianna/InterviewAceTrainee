from pathlib import PurePath
from typing import Annotated

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
    "audio/m4a",
    "audio/mp3",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
    "audio/pcm",
    "audio/silk",
    "audio/speex",
    "audio/wav",
    "audio/amr",
    "audio/x-m4a",
    "audio/x-wav",
}
ALLOWED_AUDIO_EXTENSIONS = {".aac", ".amr", ".m4a", ".mp3", ".ogg", ".pcm", ".silk", ".speex", ".wav"}
TENCENT_SENTENCE_RECOGNITION_MAX_SECONDS = 60
TENCENT_SENTENCE_RECOGNITION_MAX_BASE64_BYTES = 3 * 1024 * 1024


@router.post("/asr", response_model=SpeechRecognitionResponse)
async def recognize_speech(
    audio_file: UploadFile = File(...),
    session_id: Annotated[str, Form(max_length=120)] = "",
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
    _validate_tencent_sentence_audio_limits(audio_bytes, audio_file)

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


def _validate_tencent_sentence_audio_limits(audio_bytes: bytes, audio_file: UploadFile) -> None:
    if _estimated_base64_size(len(audio_bytes)) > TENCENT_SENTENCE_RECOGNITION_MAX_BASE64_BYTES:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="audio_file_too_large")
    if _is_wav_upload(audio_file):
        duration_ms = _estimate_wav_duration_ms(audio_bytes)
        if duration_ms is not None and duration_ms > TENCENT_SENTENCE_RECOGNITION_MAX_SECONDS * 1000:
            raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="audio_duration_too_long")


def _estimated_base64_size(byte_length: int) -> int:
    return ((byte_length + 2) // 3) * 4


def _is_wav_upload(audio_file: UploadFile) -> bool:
    content_type = (audio_file.content_type or "").split(";")[0].strip().lower()
    extension = PurePath(audio_file.filename or "").suffix.lower()
    return "wav" in content_type or extension == ".wav"


def _estimate_wav_duration_ms(audio_bytes: bytes) -> int | None:
    if len(audio_bytes) < 44 or audio_bytes[:4] != b"RIFF":
        return None
    channels = int.from_bytes(audio_bytes[22:24], "little")
    sample_rate = int.from_bytes(audio_bytes[24:28], "little")
    bits_per_sample = int.from_bytes(audio_bytes[34:36], "little")
    data_size = int.from_bytes(audio_bytes[40:44], "little")
    bytes_per_second = sample_rate * channels * bits_per_sample / 8
    if bytes_per_second <= 0:
        return None
    return int(data_size / bytes_per_second * 1000)


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
