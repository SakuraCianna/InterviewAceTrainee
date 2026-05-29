from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.entities import User
from app.schemas.admin import (
    AdminCreditAdjustmentRequest,
    AdminCreditAdjustmentResponse,
    AdminVoucherIssueRequest,
    AdminVoucherIssueResponse,
)
from app.services.audit_logs import DatabaseAuditLogStore, InMemoryAuditLogStore
from app.services.credit_balances import CreditBalanceStore
from app.services.credit_ledger import CreditLedgerStore
from app.services.credits import InsufficientCreditsError
from app.services.interview_vouchers import InterviewVoucherStore
from app.services.user_credentials import UserCredentialStore


class CreditBalanceCannotBeNegativeError(Exception):
    pass


class VoucherRecipientsRequiredError(Exception):
    pass


class VoucherUserNotFoundError(Exception):
    def __init__(self, email: str) -> None:
        self.email = email
        super().__init__(email)


class VoucherUserDisabledError(Exception):
    def __init__(self, email: str) -> None:
        self.email = email
        super().__init__(email)


def normalize_user_email(user_id: str) -> str:
    return user_id.strip().lower()


def adjust_user_credits_with_stores(
    user_id: str,
    payload: AdminCreditAdjustmentRequest,
    admin_email: str,
    credit_store: CreditBalanceStore,
    credit_ledger_store: CreditLedgerStore,
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore,
    ip_address: str | None,
    user_agent: str | None,
) -> AdminCreditAdjustmentResponse:
    normalized_user_id = normalize_user_email(user_id)
    balance_before = credit_store.get_balance(normalized_user_id)
    try:
        balance_after = credit_store.adjust(normalized_user_id, payload.change_amount)
    except InsufficientCreditsError as exc:
        raise CreditBalanceCannotBeNegativeError from exc

    audit_store.record(
        admin_email=admin_email,
        action="credit_adjust",
        target_type="user_credit",
        target_id=normalized_user_id,
        before_snapshot={"balance": balance_before},
        after_snapshot={
            "balance": balance_after,
            "change_amount": payload.change_amount,
            "reason": payload.reason,
            "note": payload.note,
        },
        ip_address=ip_address,
        user_agent=user_agent,
    )
    credit_ledger_store.record(
        user_email=normalized_user_id,
        change_amount=payload.change_amount,
        balance_after=balance_after,
        reason=payload.reason,
        operator_admin_email=admin_email,
        note=payload.note or "admin_manual_adjustment",
    )
    return AdminCreditAdjustmentResponse(
        user_id=normalized_user_id,
        change_amount=payload.change_amount,
        balance_after=balance_after,
        reason=payload.reason,
        operator_admin_id=admin_email,
    )


def resolve_voucher_recipients(
    payload: AdminVoucherIssueRequest,
    user_store: UserCredentialStore,
    db_session: Session | None,
) -> list[str]:
    if payload.issue_all_active_users:
        if db_session is not None:
            rows = db_session.execute(
                select(User.email).where(User.is_active.is_(True), User.role == "user").order_by(User.created_at.desc())
            ).scalars()
            return [normalize_user_email(email) for email in rows]
        return [
            record.email
            for record in user_store.search_users("", limit=10000)
            if record.is_active and record.role == "user"
        ]

    recipients: list[str] = []
    for email_value in payload.user_emails:
        email = normalize_user_email(str(email_value))
        if email in recipients:
            continue
        record = user_store.get_user_record(email)
        if record is None:
            raise VoucherUserNotFoundError(email)
        if not record.is_active:
            raise VoucherUserDisabledError(email)
        recipients.append(email)
    if not recipients:
        raise VoucherRecipientsRequiredError
    return recipients


def issue_vouchers_with_stores(
    payload: AdminVoucherIssueRequest,
    admin_email: str,
    target_emails: list[str],
    voucher_store: InterviewVoucherStore,
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore,
    ip_address: str | None,
    user_agent: str | None,
) -> AdminVoucherIssueResponse:
    if not target_emails:
        raise VoucherRecipientsRequiredError
    issued_vouchers = voucher_store.issue_many(
        target_emails,
        voucher_type=payload.voucher_type,
        issue_reason=payload.reason,
        quantity=payload.quantity,
        scope_interview_type=payload.interview_type,
        issued_by_admin_email=admin_email,
        note=payload.note,
    )
    total_vouchers = len(issued_vouchers)
    audit_store.record(
        admin_email=admin_email,
        action="voucher_issue",
        target_type="interview_voucher",
        target_id="all_active_users" if payload.issue_all_active_users else ",".join(target_emails[:5]),
        after_snapshot={
            "recipients": target_emails,
            "total_recipients": len(target_emails),
            "quantity_per_user": payload.quantity,
            "total_vouchers": total_vouchers,
            "voucher_type": payload.voucher_type,
            "reason": payload.reason,
            "interview_type": str(payload.interview_type) if payload.interview_type else None,
            "note": payload.note,
        },
        ip_address=ip_address,
        user_agent=user_agent,
    )
    return AdminVoucherIssueResponse(
        total_recipients=len(target_emails),
        total_vouchers=total_vouchers,
        recipients=target_emails,
        voucher_type=payload.voucher_type,
        reason=payload.reason,
        operator_admin_email=admin_email,
    )
