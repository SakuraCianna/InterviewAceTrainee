import base64
import hashlib
import hmac
import json
from dataclasses import dataclass
from random import randint
from time import time
from urllib.parse import urlencode
from uuid import uuid4

from tencentcloud.asr.v20190614 import asr_client, models as asr_models
from tencentcloud.common import credential
from tencentcloud.common.exception.tencent_cloud_sdk_exception import TencentCloudSDKException
from tencentcloud.common.profile.client_profile import ClientProfile
from tencentcloud.common.profile.http_profile import HttpProfile
from tencentcloud.tts.v20190823 import models as tts_models
from tencentcloud.tts.v20190823 import tts_client

from app.core.config import Settings
from app.schemas.interviews import InterviewType
from app.services.ai_router import AIProviderConfig


class SpeechProviderError(RuntimeError):
    """Raised when a speech provider cannot complete a request."""


@dataclass(frozen=True)
class SpeechRecognitionResult:
    text: str
    provider_request_id: str | None = None
    audio_duration_ms: int | None = None

    @property
    def call_metadata(self) -> dict:
        return {
            "provider_request_id": self.provider_request_id,
            "audio_duration_ms": self.audio_duration_ms,
            "characters": len(self.text),
        }


@dataclass(frozen=True)
class SpeechSynthesisResult:
    audio_base64: str
    mime_type: str
    provider_request_id: str | None = None
    characters: int | None = None

    @property
    def call_metadata(self) -> dict:
        return {
            "provider_request_id": self.provider_request_id,
            "characters": self.characters,
        }


class TencentSpeechClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def recognize(
        self,
        config: AIProviderConfig,
        *,
        audio_bytes: bytes,
        content_type: str,
        filename: str | None,
        session_id: str,
        interview_type: InterviewType | None = None,
    ) -> SpeechRecognitionResult:
        self._ensure_tencent_provider(config)
        self._ensure_credentials()
        audio_format = self._resolve_audio_format(content_type=content_type, filename=filename)
        client = self._asr_client()
        request = asr_models.SentenceRecognitionRequest()
        request.from_json_string(
            json.dumps(
                {
                    "ProjectId": 0,
                    "SubServiceType": 2,
                    "EngSerViceType": self._resolve_asr_engine(interview_type),
                    "SourceType": 1,
                    "VoiceFormat": audio_format,
                    "UsrAudioKey": session_id or str(uuid4()),
                    "Data": base64.b64encode(audio_bytes).decode("ascii"),
                }
            )
        )
        try:
            response = client.SentenceRecognition(request)
        except TencentCloudSDKException as exc:
            raise SpeechProviderError(exc.message or exc.code or "tencent_asr_failed") from exc

        payload = json.loads(response.to_json_string())
        text = str(payload.get("Result") or "").strip()
        if not text:
            raise SpeechProviderError("tencent_asr_empty_result")
        return SpeechRecognitionResult(
            text=text,
            provider_request_id=str(payload.get("RequestId") or "") or None,
            audio_duration_ms=_estimate_audio_duration_ms(audio_bytes, audio_format),
        )

    def synthesize(
        self,
        config: AIProviderConfig,
        *,
        text: str,
        session_id: str,
    ) -> SpeechSynthesisResult:
        self._ensure_tencent_provider(config)
        self._ensure_credentials()
        client = self._tts_client()
        request = tts_models.TextToVoiceRequest()
        request.from_json_string(
            json.dumps(
                {
                    "Text": text,
                    "SessionId": session_id or str(uuid4()),
                    "ModelType": 1,
                    "VoiceType": self._settings.tencent_tts_voice_type,
                    "Codec": "mp3",
                }
            )
        )
        try:
            response = client.TextToVoice(request)
        except TencentCloudSDKException as exc:
            raise SpeechProviderError(exc.message or exc.code or "tencent_tts_failed") from exc

        payload = json.loads(response.to_json_string())
        audio = str(payload.get("Audio") or "").strip()
        if not audio:
            raise SpeechProviderError("tencent_tts_empty_audio")
        return SpeechSynthesisResult(
            audio_base64=audio,
            mime_type="audio/mpeg",
            provider_request_id=str(payload.get("RequestId") or "") or None,
            characters=len(text),
        )

    def build_realtime_asr_url(
        self,
        config: AIProviderConfig,
        *,
        voice_id: str,
        interview_type: InterviewType | None = None,
    ) -> str:
        self._ensure_tencent_provider(config)
        self._ensure_credentials()
        self._ensure_app_id()
        timestamp = int(time())
        params: dict[str, str | int] = {
            "engine_model_type": self._resolve_asr_engine(interview_type),
            "expired": timestamp + 3600,
            "filter_dirty": 0,
            "filter_modal": 0,
            "filter_punc": 0,
            "needvad": self._settings.tencent_realtime_asr_need_vad,
            "nonce": randint(100000, 9999999999),
            "secretid": self._settings.tencent_cloud_secret_id,
            "timestamp": timestamp,
            "voice_format": 1,
            "voice_id": voice_id,
        }
        query_without_signature = urlencode(sorted(params.items()))
        signing_text = f"asr.cloud.tencent.com/asr/v2/{self._settings.tencent_cloud_app_id}?{query_without_signature}"
        signature = base64.b64encode(
            hmac.new(
                self._settings.tencent_cloud_secret_key.encode("utf-8"),
                signing_text.encode("utf-8"),
                hashlib.sha1,
            ).digest()
        ).decode("utf-8")
        params["signature"] = signature
        query = urlencode(sorted(params.items()))
        return f"wss://asr.cloud.tencent.com/asr/v2/{self._settings.tencent_cloud_app_id}?{query}"

    def _ensure_tencent_provider(self, config: AIProviderConfig) -> None:
        if config.provider_name.strip().lower() != "tencent":
            raise SpeechProviderError("provider_not_supported")

    def _ensure_credentials(self) -> None:
        if not self._settings.tencent_cloud_secret_id or not self._settings.tencent_cloud_secret_key:
            raise SpeechProviderError("tencent_cloud_credentials_missing")

    def _ensure_app_id(self) -> None:
        if not self._settings.tencent_cloud_app_id:
            raise SpeechProviderError("tencent_cloud_app_id_missing")

    def _credential(self) -> credential.Credential:
        return credential.Credential(self._settings.tencent_cloud_secret_id, self._settings.tencent_cloud_secret_key)

    def _asr_client(self) -> asr_client.AsrClient:
        http_profile = HttpProfile()
        http_profile.endpoint = "asr.tencentcloudapi.com"
        client_profile = ClientProfile()
        client_profile.httpProfile = http_profile
        return asr_client.AsrClient(self._credential(), "ap-guangzhou", client_profile)

    def _tts_client(self) -> tts_client.TtsClient:
        http_profile = HttpProfile()
        http_profile.endpoint = "tts.tencentcloudapi.com"
        client_profile = ClientProfile()
        client_profile.httpProfile = http_profile
        return tts_client.TtsClient(self._credential(), "ap-guangzhou", client_profile)

    def _resolve_audio_format(self, *, content_type: str, filename: str | None) -> str:
        normalized_content_type = content_type.lower()
        normalized_filename = (filename or "").lower()
        if "wav" in normalized_content_type or normalized_filename.endswith(".wav"):
            return "wav"
        if "mpeg" in normalized_content_type or "mp3" in normalized_content_type or normalized_filename.endswith(".mp3"):
            return "mp3"
        if "mp4" in normalized_content_type or normalized_filename.endswith(".m4a"):
            return "m4a"
        if "aac" in normalized_content_type or normalized_filename.endswith(".aac"):
            return "aac"
        if "pcm" in normalized_content_type or normalized_filename.endswith(".pcm"):
            return "pcm"
        if "amr" in normalized_content_type or normalized_filename.endswith(".amr"):
            return "amr"
        if "speex" in normalized_content_type or normalized_filename.endswith(".speex"):
            return "speex"
        if "silk" in normalized_content_type or normalized_filename.endswith(".silk"):
            return "silk"
        if "ogg" in normalized_content_type or normalized_filename.endswith(".ogg"):
            return "ogg-opus"
        raise SpeechProviderError("unsupported_audio_format")

    def _resolve_asr_engine(self, interview_type: InterviewType | None) -> str:
        if interview_type == InterviewType.IELTS:
            return self._settings.tencent_asr_ielts_engine_model_type
        return self._settings.tencent_asr_engine_model_type


def _estimate_audio_duration_ms(audio_bytes: bytes, audio_format: str) -> int | None:
    if audio_format != "wav" or len(audio_bytes) < 44 or audio_bytes[:4] != b"RIFF":
        return None
    try:
        channels = int.from_bytes(audio_bytes[22:24], "little")
        sample_rate = int.from_bytes(audio_bytes[24:28], "little")
        bits_per_sample = int.from_bytes(audio_bytes[34:36], "little")
        data_size = int.from_bytes(audio_bytes[40:44], "little")
    except ValueError:
        return None
    bytes_per_second = sample_rate * channels * bits_per_sample / 8
    if bytes_per_second <= 0:
        return None
    return int(data_size / bytes_per_second * 1000)
