from pydantic import BaseModel, Field

from app.schemas.interviews import InterviewType


class SpeechSynthesisRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1200)
    session_id: str = Field(min_length=1, max_length=120)
    interview_type: InterviewType | None = None


class SpeechSynthesisResponse(BaseModel):
    audio_base64: str
    mime_type: str
    provider_id: str


class SpeechRecognitionResponse(BaseModel):
    text: str
    provider_id: str
