from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import ContentSafetyLog
from app.services.content_safety import ContentSafetyDecision, content_excerpt


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


@dataclass(frozen=True)
class ContentSafetyLogRecord:
    id: str
    user_email: str | None
    session_id: str | None
    source: str
    action: str
    risk_level: str
    categories: list[str]
    matched_terms: list[str]
    content_excerpt: str | None
    message_code: str | None
    created_at: str


class ContentSafetyLogStore:
    def record_decision(
        self,
        *,
        user_email: str | None,
        session_id: str | None,
        source: str,
        decision: ContentSafetyDecision,
        content: str,
    ) -> None:
        raise NotImplementedError

    def list_recent(self, limit: int = 80) -> list[ContentSafetyLogRecord]:
        raise NotImplementedError


class InMemoryContentSafetyLogStore(ContentSafetyLogStore):
    def __init__(self) -> None:
        self._records: list[ContentSafetyLogRecord] = []

    def record_decision(
        self,
        *,
        user_email: str | None,
        session_id: str | None,
        source: str,
        decision: ContentSafetyDecision,
        content: str,
    ) -> None:
        if not decision.categories:
            return
        self._records.append(
            ContentSafetyLogRecord(
                id=f"memory-{len(self._records) + 1}",
                user_email=user_email,
                session_id=session_id,
                source=source,
                action=decision.action,
                risk_level=decision.risk_level,
                categories=decision.categories,
                matched_terms=decision.matched_terms,
                content_excerpt=content_excerpt(content),
                message_code=decision.message_code,
                created_at=utc_now().isoformat(),
            )
        )

    def list_recent(self, limit: int = 80) -> list[ContentSafetyLogRecord]:
        return list(reversed(self._records))[:limit]


class DatabaseContentSafetyLogStore(ContentSafetyLogStore):
    def __init__(self, session: Session) -> None:
        self._session = session

    def record_decision(
        self,
        *,
        user_email: str | None,
        session_id: str | None,
        source: str,
        decision: ContentSafetyDecision,
        content: str,
    ) -> None:
        if not decision.categories:
            return
        self._session.add(
            ContentSafetyLog(
                user_email=user_email,
                session_id=session_id,
                source=source,
                action=decision.action,
                risk_level=decision.risk_level,
                categories_json=decision.categories,
                matched_terms_json=decision.matched_terms,
                content_excerpt=content_excerpt(content),
                message_code=decision.message_code,
            )
        )
        self._session.commit()

    def list_recent(self, limit: int = 80) -> list[ContentSafetyLogRecord]:
        rows = self._session.execute(
            select(ContentSafetyLog).order_by(ContentSafetyLog.created_at.desc()).limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def _to_record(self, model: ContentSafetyLog) -> ContentSafetyLogRecord:
        return ContentSafetyLogRecord(
            id=model.id,
            user_email=model.user_email,
            session_id=model.session_id,
            source=model.source,
            action=model.action,
            risk_level=model.risk_level,
            categories=list(model.categories_json or []),
            matched_terms=list(model.matched_terms_json or []),
            content_excerpt=model.content_excerpt,
            message_code=model.message_code,
            created_at=model.created_at.isoformat(),
        )


def get_optional_content_safety_log_db_session():
    yield from get_optional_db_session(("content_safety_logs",))


memory_content_safety_log_store = InMemoryContentSafetyLogStore()


def get_content_safety_log_store(
    db_session: Session | None = Depends(get_optional_content_safety_log_db_session),
) -> ContentSafetyLogStore:
    if db_session is None:
        return memory_content_safety_log_store
    return DatabaseContentSafetyLogStore(db_session)
