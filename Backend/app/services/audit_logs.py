from collections.abc import Generator

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import AdminAuditLog
from app.schemas.admin import AdminAuditLogResponse


class InMemoryAuditLogStore:
    def __init__(self) -> None:
        self._entries: list[AdminAuditLogResponse] = []

    def record(
        self,
        admin_email: str,
        action: str,
        target_type: str,
        target_id: str,
        before_snapshot: dict | None = None,
        after_snapshot: dict | None = None,
        ip_address: str | None = None,
        user_agent: str | None = None,
    ) -> AdminAuditLogResponse:
        entry = AdminAuditLogResponse(
            id=str(len(self._entries) + 1),
            admin_email=admin_email,
            action=action,
            target_type=target_type,
            target_id=target_id,
            before_snapshot=before_snapshot,
            after_snapshot=after_snapshot,
            ip_address=ip_address,
            user_agent=user_agent,
            created_at="memory",
        )
        self._entries.append(entry)
        return entry

    def list_recent(self, limit: int = 50) -> list[AdminAuditLogResponse]:
        return list(reversed(self._entries[-limit:]))


class DatabaseAuditLogStore:
    def __init__(self, session: Session) -> None:
        self._session = session

    def record(
        self,
        admin_email: str,
        action: str,
        target_type: str,
        target_id: str,
        before_snapshot: dict | None = None,
        after_snapshot: dict | None = None,
        ip_address: str | None = None,
        user_agent: str | None = None,
    ) -> AdminAuditLogResponse:
        model = AdminAuditLog(
            admin_email=admin_email,
            action=action,
            target_type=target_type,
            target_id=target_id,
            before_snapshot=before_snapshot,
            after_snapshot=after_snapshot,
            ip_address=ip_address,
            user_agent=user_agent,
        )
        self._session.add(model)
        self._session.commit()
        return self._to_response(model)

    def list_recent(self, limit: int = 50) -> list[AdminAuditLogResponse]:
        rows = self._session.execute(select(AdminAuditLog).order_by(AdminAuditLog.created_at.desc()).limit(limit)).scalars()
        return [self._to_response(row) for row in rows]

    def _to_response(self, model: AdminAuditLog) -> AdminAuditLogResponse:
        return AdminAuditLogResponse(
            id=str(model.id),
            admin_email=model.admin_email,
            action=model.action,
            target_type=model.target_type,
            target_id=model.target_id,
            before_snapshot=model.before_snapshot,
            after_snapshot=model.after_snapshot,
            ip_address=model.ip_address,
            user_agent=model.user_agent,
            created_at=model.created_at.isoformat(),
        )


memory_audit_log_store = InMemoryAuditLogStore()


def get_optional_audit_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(("admin_audit_logs",))


def get_audit_log_store(
    db_session: Session | None = Depends(get_optional_audit_db_session),
) -> DatabaseAuditLogStore | InMemoryAuditLogStore:
    if db_session is None:
        return memory_audit_log_store
    return DatabaseAuditLogStore(db_session)
