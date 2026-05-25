from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import CustomerServiceNote


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def normalize_email(email: str) -> str:
    return email.strip().lower()


@dataclass(frozen=True)
class CustomerServiceNoteRecord:
    id: str
    user_email: str
    admin_email: str
    category: str
    content: str
    related_session_id: str | None
    created_at: str


class CustomerServiceNoteStore:
    def create(
        self,
        *,
        user_email: str,
        admin_email: str,
        category: str,
        content: str,
        related_session_id: str | None = None,
    ) -> CustomerServiceNoteRecord:
        raise NotImplementedError

    def list_for_user(self, user_email: str, limit: int = 80) -> list[CustomerServiceNoteRecord]:
        raise NotImplementedError


class InMemoryCustomerServiceNoteStore(CustomerServiceNoteStore):
    def __init__(self) -> None:
        self._records: list[CustomerServiceNoteRecord] = []

    def create(
        self,
        *,
        user_email: str,
        admin_email: str,
        category: str,
        content: str,
        related_session_id: str | None = None,
    ) -> CustomerServiceNoteRecord:
        record = CustomerServiceNoteRecord(
            id=f"memory-{len(self._records) + 1}",
            user_email=normalize_email(user_email),
            admin_email=normalize_email(admin_email),
            category=category,
            content=content,
            related_session_id=related_session_id,
            created_at=utc_now().isoformat(),
        )
        self._records.append(record)
        return record

    def list_for_user(self, user_email: str, limit: int = 80) -> list[CustomerServiceNoteRecord]:
        normalized_email = normalize_email(user_email)
        records = [record for record in self._records if record.user_email == normalized_email]
        return list(reversed(records))[:limit]


class DatabaseCustomerServiceNoteStore(CustomerServiceNoteStore):
    def __init__(self, session: Session, commit_on_write: bool = True) -> None:
        self._session = session
        self._commit_on_write = commit_on_write

    def create(
        self,
        *,
        user_email: str,
        admin_email: str,
        category: str,
        content: str,
        related_session_id: str | None = None,
    ) -> CustomerServiceNoteRecord:
        model = CustomerServiceNote(
            user_email=normalize_email(user_email),
            admin_email=normalize_email(admin_email),
            category=category,
            content=content,
            related_session_id=related_session_id,
        )
        self._session.add(model)
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_record(model)

    def list_for_user(self, user_email: str, limit: int = 80) -> list[CustomerServiceNoteRecord]:
        normalized_email = normalize_email(user_email)
        rows = self._session.execute(
            select(CustomerServiceNote)
            .where(CustomerServiceNote.user_email == normalized_email)
            .order_by(CustomerServiceNote.created_at.desc())
            .limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def _to_record(self, model: CustomerServiceNote) -> CustomerServiceNoteRecord:
        return CustomerServiceNoteRecord(
            id=str(model.id),
            user_email=model.user_email,
            admin_email=model.admin_email,
            category=model.category,
            content=model.content,
            related_session_id=model.related_session_id,
            created_at=model.created_at.isoformat(),
        )


memory_customer_service_note_store = InMemoryCustomerServiceNoteStore()


def get_optional_customer_service_note_db_session():
    yield from get_optional_db_session(("customer_service_notes",))


def get_customer_service_note_store(
    db_session: Session | None = Depends(get_optional_customer_service_note_db_session),
) -> CustomerServiceNoteStore:
    if db_session is None:
        return memory_customer_service_note_store
    return DatabaseCustomerServiceNoteStore(db_session)
