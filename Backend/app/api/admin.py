from fastapi import APIRouter, Depends, HTTPException, status

from app.api.dependencies import TokenClaims, require_admin_user
from app.schemas.admin import (
    AdminAICallLogResponse,
    AdminAuditLogResponse,
    AdminCreditAdjustmentRequest,
    AdminCreditAdjustmentResponse,
    AdminCreditLedgerResponse,
)
from app.services.audit_logs import DatabaseAuditLogStore, InMemoryAuditLogStore, get_audit_log_store
from app.services.ai_call_logs import AICallLogStore, get_ai_call_log_store
from app.services.credit_balances import CreditBalanceStore, get_credit_balance_store
from app.services.credit_ledger import CreditLedgerStore, get_credit_ledger_store
from app.services.credits import InsufficientCreditsError

router = APIRouter(prefix="/admin", tags=["admin"])


@router.post("/users/{user_id}/credits", response_model=AdminCreditAdjustmentResponse)
def adjust_user_credits(
    user_id: str,
    payload: AdminCreditAdjustmentRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    credit_ledger_store: CreditLedgerStore = Depends(get_credit_ledger_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
) -> AdminCreditAdjustmentResponse:
    balance_before = credit_store.get_balance(user_id)
    try:
        balance_after = credit_store.adjust(user_id, payload.change_amount)
    except InsufficientCreditsError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="credit_balance_cannot_be_negative") from exc

    audit_store.record(
        admin_email=admin_claims["sub"],
        action="credit_adjust",
        target_type="user_credit",
        target_id=user_id,
        before_snapshot={"balance": balance_before},
        after_snapshot={"balance": balance_after, "change_amount": payload.change_amount, "reason": payload.reason},
    )
    credit_ledger_store.record(
        user_email=user_id,
        change_amount=payload.change_amount,
        balance_after=balance_after,
        reason=payload.reason,
        operator_admin_email=admin_claims["sub"],
        note="admin_manual_adjustment",
    )
    return AdminCreditAdjustmentResponse(
        user_id=user_id,
        change_amount=payload.change_amount,
        balance_after=balance_after,
        reason=payload.reason,
        operator_admin_id=admin_claims["sub"],
    )


@router.get("/audit-logs", response_model=list[AdminAuditLogResponse])
def read_audit_logs(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
) -> list[AdminAuditLogResponse]:
    return audit_store.list_recent(limit=80)


@router.get("/users/{user_id}/credit-ledger", response_model=list[AdminCreditLedgerResponse])
def read_user_credit_ledger(
    user_id: str,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    credit_ledger_store: CreditLedgerStore = Depends(get_credit_ledger_store),
) -> list[AdminCreditLedgerResponse]:
    return credit_ledger_store.list_for_user(user_id, limit=80)


@router.get("/ai-call-logs", response_model=list[AdminAICallLogResponse])
def read_ai_call_logs(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    ai_call_log_store: AICallLogStore = Depends(get_ai_call_log_store),
) -> list[AdminAICallLogResponse]:
    return ai_call_log_store.list_recent(limit=80)
