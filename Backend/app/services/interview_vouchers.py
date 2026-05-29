from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import InterviewVoucher
from app.schemas.interviews import InterviewType


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def normalize_email(email: str) -> str:
    return email.strip().lower()


@dataclass(frozen=True)
class InterviewVoucherRecord:
    id: str
    user_email: str
    voucher_type: str
    scope_interview_type: str | None
    remaining_uses: int
    status: str
    issue_reason: str
    issued_by_admin_email: str | None
    note: str | None
    redeemed_session_id: str | None
    redeemed_at: str | None
    expires_at: str | None
    created_at: str


class InterviewVoucherStore:
    def issue(
        self,
        user_email: str,
        voucher_type: str,
        issue_reason: str,
        *,
        quantity: int = 1,
        scope_interview_type: InterviewType | None = None,
        issued_by_admin_email: str | None = None,
        note: str | None = None,
        expires_at: datetime | None = None,
    ) -> InterviewVoucherRecord:
        raise NotImplementedError

    def issue_many(
        self,
        user_emails: list[str],
        voucher_type: str,
        issue_reason: str,
        *,
        quantity: int = 1,
        scope_interview_type: InterviewType | None = None,
        issued_by_admin_email: str | None = None,
        note: str | None = None,
        expires_at: datetime | None = None,
    ) -> list[InterviewVoucherRecord]:
        records: list[InterviewVoucherRecord] = []
        for email in user_emails:
            for _ in range(quantity):
                records.append(
                    self.issue(
                        user_email=email,
                        voucher_type=voucher_type,
                        issue_reason=issue_reason,
                        quantity=1,
                        scope_interview_type=scope_interview_type,
                        issued_by_admin_email=issued_by_admin_email,
                        note=note,
                        expires_at=expires_at,
                    )
                )
        return records

    def redeem_for_interview(
        self,
        user_email: str,
        session_id: str,
        interview_type: InterviewType,
    ) -> InterviewVoucherRecord | None:
        raise NotImplementedError

    def available_count(self, user_email: str, interview_type: InterviewType | None = None) -> int:
        raise NotImplementedError

    def list_for_user(self, user_email: str, limit: int = 50) -> list[InterviewVoucherRecord]:
        raise NotImplementedError


class InMemoryInterviewVoucherStore(InterviewVoucherStore):
    def __init__(self) -> None:
        self._records: list[dict] = []

    def issue(
        self,
        user_email: str,
        voucher_type: str,
        issue_reason: str,
        *,
        quantity: int = 1,
        scope_interview_type: InterviewType | None = None,
        issued_by_admin_email: str | None = None,
        note: str | None = None,
        expires_at: datetime | None = None,
    ) -> InterviewVoucherRecord:
        if quantity <= 0:
            raise ValueError("voucher quantity must be positive")
        record = {
            "id": f"memory-voucher-{len(self._records) + 1}",
            "user_email": normalize_email(user_email),
            "voucher_type": voucher_type,
            "scope_interview_type": str(scope_interview_type) if scope_interview_type is not None else None,
            "remaining_uses": quantity,
            "status": "available",
            "issue_reason": issue_reason,
            "issued_by_admin_email": normalize_email(issued_by_admin_email) if issued_by_admin_email else None,
            "note": note,
            "redeemed_session_id": None,
            "redeemed_at": None,
            "expires_at": expires_at,
            "created_at": utc_now(),
        }
        self._records.append(record)
        return self._to_record(record)

    def redeem_for_interview(
        self,
        user_email: str,
        session_id: str,
        interview_type: InterviewType,
    ) -> InterviewVoucherRecord | None:
        normalized_email = normalize_email(user_email)
        now = utc_now()
        for record in sorted(self._records, key=lambda item: item["created_at"]):
            if not self._is_redeemable(record, normalized_email, interview_type, now):
                continue
            record["remaining_uses"] -= 1
            record["redeemed_session_id"] = session_id
            record["redeemed_at"] = now
            if record["remaining_uses"] <= 0:
                record["status"] = "redeemed"
            return self._to_record(record)
        return None

    def available_count(self, user_email: str, interview_type: InterviewType | None = None) -> int:
        normalized_email = normalize_email(user_email)
        now = utc_now()
        return sum(
            int(record["remaining_uses"])
            for record in self._records
            if self._is_redeemable(record, normalized_email, interview_type, now)
        )

    def list_for_user(self, user_email: str, limit: int = 50) -> list[InterviewVoucherRecord]:
        normalized_email = normalize_email(user_email)
        records = [record for record in self._records if record["user_email"] == normalized_email]
        return [self._to_record(record) for record in sorted(records, key=lambda item: item["created_at"], reverse=True)[:limit]]

    def _is_redeemable(
        self,
        record: dict,
        user_email: str,
        interview_type: InterviewType | None,
        now: datetime,
    ) -> bool:
        scope = record["scope_interview_type"]
        return (
            record["user_email"] == user_email
            and record["status"] == "available"
            and record["remaining_uses"] > 0
            and (record["expires_at"] is None or record["expires_at"] > now)
            and (interview_type is None or scope is None or scope == str(interview_type))
        )

    def _to_record(self, record: dict) -> InterviewVoucherRecord:
        return InterviewVoucherRecord(
            id=record["id"],
            user_email=record["user_email"],
            voucher_type=record["voucher_type"],
            scope_interview_type=record["scope_interview_type"],
            remaining_uses=record["remaining_uses"],
            status=record["status"],
            issue_reason=record["issue_reason"],
            issued_by_admin_email=record["issued_by_admin_email"],
            note=record["note"],
            redeemed_session_id=record["redeemed_session_id"],
            redeemed_at=record["redeemed_at"].isoformat() if record["redeemed_at"] else None,
            expires_at=record["expires_at"].isoformat() if record["expires_at"] else None,
            created_at=record["created_at"].isoformat(),
        )


class DatabaseInterviewVoucherStore(InterviewVoucherStore):
    def __init__(self, session: Session, commit_on_write: bool = True) -> None:
        self._session = session
        self._commit_on_write = commit_on_write

    def issue(
        self,
        user_email: str,
        voucher_type: str,
        issue_reason: str,
        *,
        quantity: int = 1,
        scope_interview_type: InterviewType | None = None,
        issued_by_admin_email: str | None = None,
        note: str | None = None,
        expires_at: datetime | None = None,
    ) -> InterviewVoucherRecord:
        if quantity <= 0:
            raise ValueError("voucher quantity must be positive")
        model = InterviewVoucher(
            user_email=normalize_email(user_email),
            voucher_type=voucher_type,
            scope_interview_type=str(scope_interview_type) if scope_interview_type is not None else None,
            remaining_uses=quantity,
            status="available",
            issue_reason=issue_reason,
            issued_by_admin_email=normalize_email(issued_by_admin_email) if issued_by_admin_email else None,
            note=note,
            expires_at=expires_at,
        )
        self._session.add(model)
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_record(model)

    def redeem_for_interview(
        self,
        user_email: str,
        session_id: str,
        interview_type: InterviewType,
    ) -> InterviewVoucherRecord | None:
        now = utc_now()
        model = self._session.execute(
            select(InterviewVoucher)
            .where(
                InterviewVoucher.user_email == normalize_email(user_email),
                InterviewVoucher.status == "available",
                InterviewVoucher.remaining_uses > 0,
                or_(InterviewVoucher.expires_at.is_(None), InterviewVoucher.expires_at > now),
                or_(InterviewVoucher.scope_interview_type.is_(None), InterviewVoucher.scope_interview_type == str(interview_type)),
            )
            .order_by(InterviewVoucher.created_at.asc())
            .with_for_update()
            .limit(1)
        ).scalar_one_or_none()
        if model is None:
            return None

        model.remaining_uses -= 1
        model.redeemed_session_id = session_id
        model.redeemed_at = now
        if model.remaining_uses <= 0:
            model.status = "redeemed"
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_record(model)

    def available_count(self, user_email: str, interview_type: InterviewType | None = None) -> int:
        now = utc_now()
        query = select(InterviewVoucher).where(
            InterviewVoucher.user_email == normalize_email(user_email),
            InterviewVoucher.status == "available",
            InterviewVoucher.remaining_uses > 0,
            or_(InterviewVoucher.expires_at.is_(None), InterviewVoucher.expires_at > now),
        )
        if interview_type is not None:
            query = query.where(or_(InterviewVoucher.scope_interview_type.is_(None), InterviewVoucher.scope_interview_type == str(interview_type)))
        rows = self._session.execute(query).scalars()
        return sum(row.remaining_uses for row in rows)

    def list_for_user(self, user_email: str, limit: int = 50) -> list[InterviewVoucherRecord]:
        rows = self._session.execute(
            select(InterviewVoucher)
            .where(InterviewVoucher.user_email == normalize_email(user_email))
            .order_by(InterviewVoucher.created_at.desc())
            .limit(limit)
        ).scalars()
        return [self._to_record(row) for row in rows]

    def _to_record(self, model: InterviewVoucher) -> InterviewVoucherRecord:
        return InterviewVoucherRecord(
            id=model.id,
            user_email=model.user_email,
            voucher_type=model.voucher_type,
            scope_interview_type=model.scope_interview_type,
            remaining_uses=model.remaining_uses,
            status=model.status,
            issue_reason=model.issue_reason,
            issued_by_admin_email=model.issued_by_admin_email,
            note=model.note,
            redeemed_session_id=model.redeemed_session_id,
            redeemed_at=model.redeemed_at.isoformat() if model.redeemed_at else None,
            expires_at=model.expires_at.isoformat() if model.expires_at else None,
            created_at=model.created_at.isoformat(),
        )


def get_optional_voucher_db_session():
    yield from get_optional_db_session(("interview_vouchers",))


memory_interview_voucher_store = InMemoryInterviewVoucherStore()


def get_interview_voucher_store(
    db_session: Session | None = Depends(get_optional_voucher_db_session),
) -> InterviewVoucherStore:
    if db_session is None:
        return memory_interview_voucher_store
    return DatabaseInterviewVoucherStore(db_session)
