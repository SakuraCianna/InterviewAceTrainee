from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import AuthLoginLog


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def normalize_email(email: str) -> str:
    return email.strip().lower()


@dataclass(frozen=True)
class AuthLoginLogRecord:
    id: str
    email: str
    auth_method: str
    role: str
    success: bool
    failure_reason: str | None
    ip_address: str | None
    user_agent: str | None
    created_at: str


class AuthLoginLogStore:
    def record(
        self,
        *,
        email: str,
        auth_method: str,
        role: str,
        success: bool,
        failure_reason: str | None = None,
        ip_address: str | None = None,
        user_agent: str | None = None,
    ) -> AuthLoginLogRecord:
        raise NotImplementedError

    def list_recent(self, limit: int = 80) -> list[AuthLoginLogRecord]:
        raise NotImplementedError

    def list_for_user(self, email: str, limit: int = 50) -> list[AuthLoginLogRecord]:
        raise NotImplementedError


class InMemoryAuthLoginLogStore(AuthLoginLogStore):
    def __init__(self) -> None:
        self._records: list[AuthLoginLogRecord] = []

    def record(
        self,
        *,
        email: str,
        auth_method: str,
        role: str,
        success: bool,
        failure_reason: str | None = None,
        ip_address: str | None = None,
        user_agent: str | None = None,
    ) -> AuthLoginLogRecord:
        record = AuthLoginLogRecord(
            id=f"memory-{len(self._records) + 1}",
            email=normalize_email(email),
            auth_method=auth_method,
            role=role,
            success=success,
            failure_reason=failure_reason,
            ip_address=ip_address,
            user_agent=user_agent,
            created_at=utc_now().isoformat(),
        )
        self._records.append(record)
        return record

    def list_recent(self, limit: int = 80) -> list[AuthLoginLogRecord]:
        return list(reversed(self._records))[:limit]

    def list_for_user(self, email: str, limit: int = 50) -> list[AuthLoginLogRecord]:
        normalized_email = normalize_email(email)
        records = [record for record in self._records if record.email == normalized_email]
        return list(reversed(records))[:limit]


class DatabaseAuthLoginLogStore(AuthLoginLogStore):
    def __init__(self, session: Session, commit_on_write: bool = True) -> None:
        self._session = session
        self._commit_on_write = commit_on_write

    def record(
        self,
        *,
        email: str,
        auth_method: str,
        role: str,
        success: bool,
        failure_reason: str | None = None,
        ip_address: str | None = None,
        user_agent: str | None = None,
    ) -> AuthLoginLogRecord:
        model = AuthLoginLog(
            email=normalize_email(email),
            auth_method=auth_method,
            role=role,
            success=success,
            failure_reason=failure_reason,
            ip_address=ip_address,
            user_agent=user_agent,
        )
        self._session.add(model)
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_record(model)

    def list_recent(self, limit: int = 80) -> list[AuthLoginLogRecord]:
        rows = self._session.execute(
            select(AuthLoginLog).order_by(AuthLoginLog.created_at.desc()).limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def list_for_user(self, email: str, limit: int = 50) -> list[AuthLoginLogRecord]:
        normalized_email = normalize_email(email)
        rows = self._session.execute(
            select(AuthLoginLog)
            .where(AuthLoginLog.email == normalized_email)
            .order_by(AuthLoginLog.created_at.desc())
            .limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def _to_record(self, model: AuthLoginLog) -> AuthLoginLogRecord:
        return AuthLoginLogRecord(
            id=str(model.id),
            email=model.email,
            auth_method=model.auth_method,
            role=model.role,
            success=model.success,
            failure_reason=model.failure_reason,
            ip_address=model.ip_address,
            user_agent=model.user_agent,
            created_at=model.created_at.isoformat(),
        )


memory_auth_login_log_store = InMemoryAuthLoginLogStore()


def get_optional_auth_login_log_db_session():
    yield from get_optional_db_session(("auth_login_logs",))


def get_auth_login_log_store(
    db_session: Session | None = Depends(get_optional_auth_login_log_db_session),
) -> AuthLoginLogStore:
    if db_session is None:
        return memory_auth_login_log_store
    return DatabaseAuthLoginLogStore(db_session)
