from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import CreditLedgerModel


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


@dataclass(frozen=True)
class CreditLedgerRecord:
    id: str
    user_email: str
    change_amount: int
    balance_after: int
    reason: str
    related_session_id: str | None
    operator_admin_email: str | None
    note: str | None
    created_at: str


class CreditLedgerStore:
    def record(
        self,
        user_email: str,
        change_amount: int,
        balance_after: int,
        reason: str,
        related_session_id: str | None = None,
        operator_admin_email: str | None = None,
        note: str | None = None,
    ) -> CreditLedgerRecord:
        raise NotImplementedError

    def list_for_user(self, user_email: str, limit: int = 50) -> list[CreditLedgerRecord]:
        raise NotImplementedError


class InMemoryCreditLedgerStore(CreditLedgerStore):
    def __init__(self) -> None:
        self._records: list[CreditLedgerRecord] = []

    def record(
        self,
        user_email: str,
        change_amount: int,
        balance_after: int,
        reason: str,
        related_session_id: str | None = None,
        operator_admin_email: str | None = None,
        note: str | None = None,
    ) -> CreditLedgerRecord:
        normalized_email = normalize_email(user_email)
        record = CreditLedgerRecord(
            id=f"memory-{len(self._records) + 1}",
            user_email=normalized_email,
            change_amount=change_amount,
            balance_after=balance_after,
            reason=reason,
            related_session_id=related_session_id,
            operator_admin_email=normalize_email(operator_admin_email) if operator_admin_email else None,
            note=note,
            created_at=utc_now().isoformat(),
        )
        self._records.append(record)
        return record

    def list_for_user(self, user_email: str, limit: int = 50) -> list[CreditLedgerRecord]:
        normalized_email = normalize_email(user_email)
        records = [record for record in self._records if record.user_email == normalized_email]
        return list(reversed(records))[:limit]


class DatabaseCreditLedgerStore(CreditLedgerStore):
    def __init__(self, session: Session, commit_on_write: bool = True) -> None:
        self._session = session
        self._commit_on_write = commit_on_write

    def record(
        self,
        user_email: str,
        change_amount: int,
        balance_after: int,
        reason: str,
        related_session_id: str | None = None,
        operator_admin_email: str | None = None,
        note: str | None = None,
    ) -> CreditLedgerRecord:
        model = CreditLedgerModel(
            user_email=normalize_email(user_email),
            change_amount=change_amount,
            balance_after=balance_after,
            reason=reason,
            related_session_id=related_session_id,
            operator_admin_email=normalize_email(operator_admin_email) if operator_admin_email else None,
            note=note,
        )
        self._session.add(model)
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_record(model)

    def list_for_user(self, user_email: str, limit: int = 50) -> list[CreditLedgerRecord]:
        normalized_email = normalize_email(user_email)
        rows = self._session.execute(
            select(CreditLedgerModel)
            .where(CreditLedgerModel.user_email == normalized_email)
            .order_by(CreditLedgerModel.created_at.desc())
            .limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def _to_record(self, model: CreditLedgerModel) -> CreditLedgerRecord:
        return CreditLedgerRecord(
            id=model.id,
            user_email=model.user_email,
            change_amount=model.change_amount,
            balance_after=model.balance_after,
            reason=model.reason,
            related_session_id=model.related_session_id,
            operator_admin_email=model.operator_admin_email,
            note=model.note,
            created_at=model.created_at.isoformat(),
        )


def normalize_email(email: str) -> str:
    return email.strip().lower()


def get_optional_credit_ledger_db_session():
    yield from get_optional_db_session(("credit_ledger",))


memory_credit_ledger_store = InMemoryCreditLedgerStore()


def get_credit_ledger_store(
    db_session: Session | None = Depends(get_optional_credit_ledger_db_session),
) -> CreditLedgerStore:
    if db_session is None:
        return memory_credit_ledger_store
    return DatabaseCreditLedgerStore(db_session)
