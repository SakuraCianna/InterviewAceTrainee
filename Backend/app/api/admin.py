from collections.abc import Generator

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
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
    AdminUserStatusResponse,
    AdminUserStatusUpdateRequest,
    SystemConfigResponse,
    SystemConfigUpdateRequest,
)
from app.schemas.interviews import InterviewHistoryItem
from app.services.audit_logs import DatabaseAuditLogStore, InMemoryAuditLogStore, get_audit_log_store
from app.services.ai_call_logs import AICallLogStore, get_ai_call_log_store
from app.services.credit_balances import CreditBalanceStore, DatabaseCreditBalanceStore, get_credit_balance_store
from app.services.credit_ledger import CreditLedgerStore, DatabaseCreditLedgerStore, get_credit_ledger_store
from app.services.credits import InsufficientCreditsError
from app.services.interview_runtime import (
    DatabaseInterviewRuntimeStore,
    InMemoryInterviewRuntimeStore,
    InterviewHistoryRecord,
    memory_interview_store,
)
from app.services.system_configs import (
    DatabaseSystemConfigStore,
    InMemorySystemConfigStore,
    SystemConfig,
    SystemConfigNotFoundError,
    get_system_config_store,
)
from app.services.user_credentials import UserAccountRecord, UserCredentialStore, get_user_credential_store

router = APIRouter(prefix="/admin", tags=["admin"])
ADMIN_INTERVIEW_REQUIRED_TABLES = ("interview_sessions", "interview_turns", "interview_reports", "interview_materials")
ADMIN_CREDIT_ADJUSTMENT_REQUIRED_TABLES = ("users", "credit_ledger", "admin_audit_logs")


def get_optional_admin_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(("users",))


def get_optional_admin_interview_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_INTERVIEW_REQUIRED_TABLES)


def get_optional_credit_adjustment_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_CREDIT_ADJUSTMENT_REQUIRED_TABLES)


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
    is_active: bool,
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
        is_active=is_active,
        credit_balance=credit_store.get_balance(normalized_email),
        total_interviews=len(interviews),
        completed_interviews=completed_interviews,
        last_interview_at=last_interview_at,
    )


def summarize_user_record(
    record: UserAccountRecord,
    credit_store: CreditBalanceStore,
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore,
) -> AdminUserSearchItem:
    return summarize_user(
        user_email=record.email,
        role=record.role,
        is_active=record.is_active,
        credit_store=credit_store,
        interview_store=interview_store,
    )


def system_config_to_response(config: SystemConfig) -> SystemConfigResponse:
    return SystemConfigResponse(
        key=config.key,
        value=config.value,
        description=config.description,
        updated_at=config.updated_at.isoformat() if config.updated_at else None,
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
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> list[AdminUserSearchItem]:
    normalized_query = normalize_user_email(query)
    database_users = search_database_users(normalized_query, db_session)
    if database_users:
        return [
            summarize_user(
                user_email=user.email,
                role=user.role,
                is_active=user.is_active,
                credit_store=credit_store,
                interview_store=interview_store,
            )
            for user in database_users
        ]

    user_records = user_store.search_users(normalized_query)
    if user_records:
        return [summarize_user_record(record, credit_store, interview_store) for record in user_records]

    if "@" not in normalized_query:
        return []
    return [
        summarize_user(
            user_email=normalized_query,
            role="user",
            is_active=user_store.is_active(normalized_query),
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
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> AdminUserDetailResponse:
    normalized_user_id = normalize_user_email(user_id)
    user = None
    if db_session is not None:
        user = db_session.execute(select(User).where(User.email == normalized_user_id)).scalar_one_or_none()

    role = user.role if user is not None else "user"
    is_active = user.is_active if user is not None else user_store.is_active(normalized_user_id)
    summary = summarize_user(normalized_user_id, role, is_active, credit_store, interview_store)
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


@router.put("/users/{user_id}/status", response_model=AdminUserStatusResponse)
def update_user_status(
    request: Request,
    user_id: str,
    payload: AdminUserStatusUpdateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
) -> AdminUserStatusResponse:
    normalized_user_id = normalize_user_email(user_id)
    before_active = user_store.is_active(normalized_user_id)
    after_active = user_store.set_active(normalized_user_id, payload.is_active)
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="user_status_update",
        target_type="user",
        target_id=normalized_user_id,
        before_snapshot={"is_active": before_active},
        after_snapshot={"is_active": after_active, "reason": payload.reason},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    return AdminUserStatusResponse(email=normalized_user_id, is_active=after_active)


@router.post("/users/{user_id}/credits", response_model=AdminCreditAdjustmentResponse)
def adjust_user_credits(
    request: Request,
    user_id: str,
    payload: AdminCreditAdjustmentRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    fallback_credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    fallback_credit_ledger_store: CreditLedgerStore = Depends(get_credit_ledger_store),
    fallback_audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
    adjustment_db_session: Session | None = Depends(get_optional_credit_adjustment_db_session),
) -> AdminCreditAdjustmentResponse:
    ip_address = request.client.host if request.client else None
    user_agent = request.headers.get("user-agent")
    if adjustment_db_session is not None:
        try:
            response = adjust_user_credits_with_stores(
                user_id=user_id,
                payload=payload,
                admin_claims=admin_claims,
                credit_store=DatabaseCreditBalanceStore(adjustment_db_session, commit_on_write=False),
                credit_ledger_store=DatabaseCreditLedgerStore(adjustment_db_session, commit_on_write=False),
                audit_store=DatabaseAuditLogStore(adjustment_db_session, commit_on_write=False),
                ip_address=ip_address,
                user_agent=user_agent,
            )
            adjustment_db_session.commit()
            return response
        except Exception:
            adjustment_db_session.rollback()
            raise

    return adjust_user_credits_with_stores(
        user_id=user_id,
        payload=payload,
        admin_claims=admin_claims,
        credit_store=fallback_credit_store,
        credit_ledger_store=fallback_credit_ledger_store,
        audit_store=fallback_audit_store,
        ip_address=ip_address,
        user_agent=user_agent,
    )


def adjust_user_credits_with_stores(
    user_id: str,
    payload: AdminCreditAdjustmentRequest,
    admin_claims: TokenClaims,
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
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="credit_balance_cannot_be_negative") from exc

    audit_store.record(
        admin_email=admin_claims["sub"],
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
        operator_admin_email=admin_claims["sub"],
        note=payload.note or "admin_manual_adjustment",
    )
    return AdminCreditAdjustmentResponse(
        user_id=normalized_user_id,
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


@router.get("/system-configs", response_model=list[SystemConfigResponse])
def read_system_configs(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    system_config_store: DatabaseSystemConfigStore | InMemorySystemConfigStore = Depends(get_system_config_store),
) -> list[SystemConfigResponse]:
    return [system_config_to_response(config) for config in system_config_store.list_configs()]


@router.put("/system-configs/{config_key}", response_model=SystemConfigResponse)
def update_system_config(
    request: Request,
    config_key: str,
    payload: SystemConfigUpdateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    system_config_store: DatabaseSystemConfigStore | InMemorySystemConfigStore = Depends(get_system_config_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
) -> SystemConfigResponse:
    try:
        before = system_config_store.get(config_key)
        updated = system_config_store.update(config_key, payload.value, payload.description)
    except SystemConfigNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="system_config_not_found") from exc

    audit_store.record(
        admin_email=admin_claims["sub"],
        action="system_config_update",
        target_type="system_config",
        target_id=config_key,
        before_snapshot={"value": before.value, "description": before.description},
        after_snapshot={"value": updated.value, "description": updated.description},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    return system_config_to_response(updated)
