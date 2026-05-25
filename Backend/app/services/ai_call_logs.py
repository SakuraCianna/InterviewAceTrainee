from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import AICallLog
from app.services.ai_router import AIProviderAttempt


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


@dataclass(frozen=True)
class AICallLogRecord:
    id: str
    session_id: str | None
    provider_type: str
    provider_name: str
    model_name: str
    purpose: str
    success: bool
    latency_ms: int | None
    provider_request_id: str | None
    input_tokens: int | None
    output_tokens: int | None
    audio_duration_ms: int | None
    characters: int | None
    estimated_cost_cents: int | None
    error_message: str | None
    usage_json: dict | None
    created_at: str


class AICallLogStore:
    def record_attempts(
        self,
        session_id: str | None,
        provider_type: str,
        purpose: str,
        attempts: list[AIProviderAttempt],
    ) -> None:
        raise NotImplementedError

    def list_recent(self, limit: int = 80) -> list[AICallLogRecord]:
        raise NotImplementedError


class InMemoryAICallLogStore(AICallLogStore):
    def __init__(self) -> None:
        self._records: list[AICallLogRecord] = []

    def record_attempts(
        self,
        session_id: str | None,
        provider_type: str,
        purpose: str,
        attempts: list[AIProviderAttempt],
    ) -> None:
        for attempt in attempts:
            self._records.append(
                AICallLogRecord(
                    id=f"memory-{len(self._records) + 1}",
                    session_id=session_id,
                    provider_type=provider_type,
                    provider_name=attempt.provider_name,
                    model_name=attempt.model_name,
                    purpose=purpose,
                    success=attempt.success,
                    latency_ms=attempt.latency_ms,
                    provider_request_id=attempt.provider_request_id,
                    input_tokens=attempt.input_tokens,
                    output_tokens=attempt.output_tokens,
                    audio_duration_ms=attempt.audio_duration_ms,
                    characters=attempt.characters,
                    estimated_cost_cents=attempt.estimated_cost_cents,
                    error_message=attempt.error_message or None,
                    usage_json=attempt.usage_json,
                    created_at=utc_now().isoformat(),
                )
            )

    def list_recent(self, limit: int = 80) -> list[AICallLogRecord]:
        return list(reversed(self._records))[:limit]


class DatabaseAICallLogStore(AICallLogStore):
    def __init__(self, session: Session) -> None:
        self._session = session

    def record_attempts(
        self,
        session_id: str | None,
        provider_type: str,
        purpose: str,
        attempts: list[AIProviderAttempt],
    ) -> None:
        for attempt in attempts:
            self._session.add(
                AICallLog(
                    session_id=session_id,
                    provider_type=provider_type,
                    provider_name=attempt.provider_name,
                    model_name=attempt.model_name,
                    purpose=purpose,
                    success=attempt.success,
                    latency_ms=attempt.latency_ms,
                    provider_request_id=attempt.provider_request_id,
                    input_tokens=attempt.input_tokens,
                    output_tokens=attempt.output_tokens,
                    audio_duration_ms=attempt.audio_duration_ms,
                    characters=attempt.characters,
                    estimated_cost_cents=attempt.estimated_cost_cents,
                    error_message=attempt.error_message or None,
                    usage_json=attempt.usage_json,
                )
            )
        self._session.commit()

    def list_recent(self, limit: int = 80) -> list[AICallLogRecord]:
        rows = self._session.execute(
            select(AICallLog).order_by(AICallLog.created_at.desc()).limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def _to_record(self, model: AICallLog) -> AICallLogRecord:
        return AICallLogRecord(
            id=model.id,
            session_id=model.session_id,
            provider_type=model.provider_type,
            provider_name=model.provider_name,
            model_name=model.model_name,
            purpose=model.purpose,
            success=model.success,
            latency_ms=model.latency_ms,
            provider_request_id=model.provider_request_id,
            input_tokens=model.input_tokens,
            output_tokens=model.output_tokens,
            audio_duration_ms=model.audio_duration_ms,
            characters=model.characters,
            estimated_cost_cents=model.estimated_cost_cents,
            error_message=model.error_message,
            usage_json=model.usage_json,
            created_at=model.created_at.isoformat(),
        )


def get_optional_ai_call_log_db_session():
    yield from get_optional_db_session(("ai_call_logs",))


memory_ai_call_log_store = InMemoryAICallLogStore()


def get_ai_call_log_store(
    db_session: Session | None = Depends(get_optional_ai_call_log_db_session),
) -> AICallLogStore:
    if db_session is None:
        return memory_ai_call_log_store
    return DatabaseAICallLogStore(db_session)
