from collections.abc import Generator

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.dependencies import TokenClaims, require_admin_user
from app.db.session import get_optional_db_session
from app.models.entities import User
from app.schemas.admin import (
    AdminAICallLogResponse,
    AdminAuditLogResponse,
    AdminCreditAdjustmentRequest,
    AdminCreditAdjustmentResponse,
    AdminCreditLedgerResponse,
    AdminUserDetailResponse,
    AdminUserInterviewReportResponse,
    AdminUserSearchItem,
)
from app.schemas.interviews import InterviewHistoryItem
from app.services.audit_logs import DatabaseAuditLogStore, InMemoryAuditLogStore, get_audit_log_store
from app.services.ai_call_logs import AICallLogStore, get_ai_call_log_store
from app.services.credit_balances import CreditBalanceStore, get_credit_balance_store
from app.services.credit_ledger import CreditLedgerStore, get_credit_ledger_store
from app.services.credits import InsufficientCreditsError
from app.services.interview_runtime import (
    DatabaseInterviewRuntimeStore,
    InMemoryInterviewRuntimeStore,
    InterviewHistoryRecord,
    memory_interview_store,
)

router = APIRouter(prefix="/admin", tags=["admin"])
ADMIN_INTERVIEW_REQUIRED_TABLES = ("interview_sessions", "interview_turns", "interview_reports", "interview_materials")


def get_optional_admin_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(("users",))


def get_optional_admin_interview_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_INTERVIEW_REQUIRED_TABLES)


def get_admin_interview_store(
    db_session: Session | None = Depends(get_optional_admin_interview_db_session),
) -> DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore:
    if db_session is None:
        return memory_interview_store
    return DatabaseInterviewRuntimeStore(db_session)


def normalize_user_email(user_id: str) -> str:
    return user_id.strip().lower()


def history_record_to_schema(record: InterviewHistoryRecord) -> InterviewHistoryItem:
    return InterviewHistoryItem(
        session_id=record.session_id,
        interview_type=record.interview_type,
        status=record.status,
        current_step_index=record.current_step_index,
        total_steps=record.total_steps,
        report_total_score=record.report_total_score,
        created_at=record.created_at,
    )


def summarize_user(
    user_email: str,
    role: str,
    credit_store: CreditBalanceStore,
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore,
) -> AdminUserSearchItem:
    normalized_email = normalize_user_email(user_email)
    interviews = interview_store.list_user_sessions(normalized_email, limit=200)
    completed_interviews = sum(1 for item in interviews if item.status == "completed")
    last_interview_at = interviews[0].created_at.isoformat() if interviews else None
    return AdminUserSearchItem(
        email=normalized_email,
        role=role,
        credit_balance=credit_store.get_balance(normalized_email),
        total_interviews=len(interviews),
        completed_interviews=completed_interviews,
        last_interview_at=last_interview_at,
    )


def search_database_users(query: str, db_session: Session | None) -> list[User]:
    if db_session is None:
        return []
    normalized_query = f"%{query.strip().lower()}%"
    return list(
        db_session.execute(
            select(User).where(User.email.ilike(normalized_query)).order_by(User.created_at.desc()).limit(20)
        ).scalars()
    )


@router.get("/users/search", response_model=list[AdminUserSearchItem])
def search_users(
    query: str = Query(min_length=1, max_length=255),
    _admin_claims: TokenClaims = Depends(require_admin_user),
    credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_admin_interview_store),
    db_session: Session | None = Depends(get_optional_admin_db_session),
) -> list[AdminUserSearchItem]:
    normalized_query = normalize_user_email(query)
    database_users = search_database_users(normalized_query, db_session)
    if database_users:
        return [
            summarize_user(
                user_email=user.email,
                role=user.role,
                credit_store=credit_store,
                interview_store=interview_store,
            )
            for user in database_users
        ]

    if "@" not in normalized_query:
        return []
    return [
        summarize_user(
            user_email=normalized_query,
            role="user",
            credit_store=credit_store,
            interview_store=interview_store,
        )
    ]


@router.get("/users/{user_id}", response_model=AdminUserDetailResponse)
def read_user_detail(
    user_id: str,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_admin_interview_store),
    db_session: Session | None = Depends(get_optional_admin_db_session),
) -> AdminUserDetailResponse:
    normalized_user_id = normalize_user_email(user_id)
    user = None
    if db_session is not None:
        user = db_session.execute(select(User).where(User.email == normalized_user_id)).scalar_one_or_none()

    role = user.role if user is not None else "user"
    summary = summarize_user(normalized_user_id, role, credit_store, interview_store)
    interviews = [history_record_to_schema(record) for record in interview_store.list_user_sessions(normalized_user_id, limit=80)]
    return AdminUserDetailResponse(**summary.model_dump(), interviews=interviews)


@router.get("/users/{user_id}/interviews", response_model=list[InterviewHistoryItem])
def read_user_interview_history(
    user_id: str,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_admin_interview_store),
) -> list[InterviewHistoryItem]:
    normalized_user_id = normalize_user_email(user_id)
    return [history_record_to_schema(record) for record in interview_store.list_user_sessions(normalized_user_id, limit=80)]


@router.get("/users/{user_id}/interviews/{session_id}/report", response_model=AdminUserInterviewReportResponse)
def read_user_interview_report(
    user_id: str,
    session_id: str,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_admin_interview_store),
) -> AdminUserInterviewReportResponse:
    normalized_user_id = normalize_user_email(user_id)
    report = interview_store.get_report(normalized_user_id, session_id)
    if report is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="interview_report_not_found")
    return AdminUserInterviewReportResponse(user_email=normalized_user_id, **report.model_dump())


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
