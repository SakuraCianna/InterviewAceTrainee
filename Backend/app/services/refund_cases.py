from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import RefundCase


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def normalize_email(email: str) -> str:
    return email.strip().lower()


class RefundCaseNotFoundError(LookupError):
    """Raised when a refund case cannot be found."""


@dataclass(frozen=True)
class RefundCaseRecord:
    id: str
    user_email: str
    status: str
    reason: str
    description: str
    amount_cents: int | None
    currency: str
    credit_adjustment: int | None
    related_session_id: str | None
    resolution: str | None
    created_by_admin_email: str
    updated_by_admin_email: str | None
    created_at: str
    updated_at: str


class RefundCaseStore:
    def create(
        self,
        *,
        user_email: str,
        reason: str,
        description: str,
        created_by_admin_email: str,
        amount_cents: int | None = None,
        currency: str = "CNY",
        credit_adjustment: int | None = None,
        related_session_id: str | None = None,
    ) -> RefundCaseRecord:
        raise NotImplementedError

    def update(
        self,
        *,
        case_id: str,
        updated_by_admin_email: str,
        status: str | None = None,
        resolution: str | None = None,
        credit_adjustment: int | None = None,
        amount_cents: int | None = None,
    ) -> RefundCaseRecord:
        raise NotImplementedError

    def list_recent(self, limit: int = 80) -> list[RefundCaseRecord]:
        raise NotImplementedError

    def list_for_user(self, user_email: str, limit: int = 80) -> list[RefundCaseRecord]:
        raise NotImplementedError


class InMemoryRefundCaseStore(RefundCaseStore):
    def __init__(self) -> None:
        self._records: list[RefundCaseRecord] = []

    def create(
        self,
        *,
        user_email: str,
        reason: str,
        description: str,
        created_by_admin_email: str,
        amount_cents: int | None = None,
        currency: str = "CNY",
        credit_adjustment: int | None = None,
        related_session_id: str | None = None,
    ) -> RefundCaseRecord:
        created_at = utc_now().isoformat()
        record = RefundCaseRecord(
            id=f"memory-{len(self._records) + 1}",
            user_email=normalize_email(user_email),
            status="open",
            reason=reason,
            description=description,
            amount_cents=amount_cents,
            currency=currency,
            credit_adjustment=credit_adjustment,
            related_session_id=related_session_id,
            resolution=None,
            created_by_admin_email=normalize_email(created_by_admin_email),
            updated_by_admin_email=None,
            created_at=created_at,
            updated_at=created_at,
        )
        self._records.append(record)
        return record

    def update(
        self,
        *,
        case_id: str,
        updated_by_admin_email: str,
        status: str | None = None,
        resolution: str | None = None,
        credit_adjustment: int | None = None,
        amount_cents: int | None = None,
    ) -> RefundCaseRecord:
        for index, record in enumerate(self._records):
            if record.id != case_id:
                continue
            updated = RefundCaseRecord(
                id=record.id,
                user_email=record.user_email,
                status=status or record.status,
                reason=record.reason,
                description=record.description,
                amount_cents=amount_cents if amount_cents is not None else record.amount_cents,
                currency=record.currency,
                credit_adjustment=credit_adjustment if credit_adjustment is not None else record.credit_adjustment,
                related_session_id=record.related_session_id,
                resolution=resolution if resolution is not None else record.resolution,
                created_by_admin_email=record.created_by_admin_email,
                updated_by_admin_email=normalize_email(updated_by_admin_email),
                created_at=record.created_at,
                updated_at=utc_now().isoformat(),
            )
            self._records[index] = updated
            return updated
        raise RefundCaseNotFoundError("refund case not found")

    def list_recent(self, limit: int = 80) -> list[RefundCaseRecord]:
        return list(reversed(self._records))[:limit]

    def list_for_user(self, user_email: str, limit: int = 80) -> list[RefundCaseRecord]:
        normalized_email = normalize_email(user_email)
        records = [record for record in self._records if record.user_email == normalized_email]
        return list(reversed(records))[:limit]


class DatabaseRefundCaseStore(RefundCaseStore):
    def __init__(self, session: Session, commit_on_write: bool = True) -> None:
        self._session = session
        self._commit_on_write = commit_on_write

    def create(
        self,
        *,
        user_email: str,
        reason: str,
        description: str,
        created_by_admin_email: str,
        amount_cents: int | None = None,
        currency: str = "CNY",
        credit_adjustment: int | None = None,
        related_session_id: str | None = None,
    ) -> RefundCaseRecord:
        model = RefundCase(
            user_email=normalize_email(user_email),
            status="open",
            reason=reason,
            description=description,
            amount_cents=amount_cents,
            currency=currency,
            credit_adjustment=credit_adjustment,
            related_session_id=related_session_id,
            created_by_admin_email=normalize_email(created_by_admin_email),
        )
        self._session.add(model)
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_record(model)

    def update(
        self,
        *,
        case_id: str,
        updated_by_admin_email: str,
        status: str | None = None,
        resolution: str | None = None,
        credit_adjustment: int | None = None,
        amount_cents: int | None = None,
    ) -> RefundCaseRecord:
        model = self._session.get(RefundCase, case_id)
        if model is None:
            raise RefundCaseNotFoundError("refund case not found")
        if status is not None:
            model.status = status
        if resolution is not None:
            model.resolution = resolution
        if credit_adjustment is not None:
            model.credit_adjustment = credit_adjustment
        if amount_cents is not None:
            model.amount_cents = amount_cents
        model.updated_by_admin_email = normalize_email(updated_by_admin_email)
        model.updated_at = utc_now()
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_record(model)

    def list_recent(self, limit: int = 80) -> list[RefundCaseRecord]:
        rows = self._session.execute(
            select(RefundCase).order_by(RefundCase.updated_at.desc()).limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def list_for_user(self, user_email: str, limit: int = 80) -> list[RefundCaseRecord]:
        normalized_email = normalize_email(user_email)
        rows = self._session.execute(
            select(RefundCase)
            .where(RefundCase.user_email == normalized_email)
            .order_by(RefundCase.updated_at.desc())
            .limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def _to_record(self, model: RefundCase) -> RefundCaseRecord:
        return RefundCaseRecord(
            id=str(model.id),
            user_email=model.user_email,
            status=model.status,
            reason=model.reason,
            description=model.description,
            amount_cents=model.amount_cents,
            currency=model.currency,
            credit_adjustment=model.credit_adjustment,
            related_session_id=model.related_session_id,
            resolution=model.resolution,
            created_by_admin_email=model.created_by_admin_email,
            updated_by_admin_email=model.updated_by_admin_email,
            created_at=model.created_at.isoformat(),
            updated_at=model.updated_at.isoformat(),
        )


memory_refund_case_store = InMemoryRefundCaseStore()


def get_optional_refund_case_db_session():
    yield from get_optional_db_session(("refund_cases",))


def get_refund_case_store(
    db_session: Session | None = Depends(get_optional_refund_case_db_session),
) -> RefundCaseStore:
    if db_session is None:
        return memory_refund_case_store
    return DatabaseRefundCaseStore(db_session)
