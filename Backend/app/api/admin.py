from collections.abc import Generator

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.api.dependencies import TokenClaims, require_admin_user
from app.db.session import get_optional_db_session
from app.models.entities import InterviewSession, User
from app.schemas.admin import (
    AdminAICallLogResponse,
    AdminAuthLoginLogResponse,
    AdminAuditLogResponse,
    AdminContentSafetyLogResponse,
    AdminCreditAdjustmentRequest,
    AdminCreditAdjustmentResponse,
    AdminCreditLedgerResponse,
    AdminDashboardStatsResponse,
    AdminUserDetailResponse,
    AdminUserListResponse,
    AdminUserInterviewReportResponse,
    AdminUserRoleResponse,
    AdminUserRoleUpdateRequest,
    AdminUserSearchItem,
    AdminUserStatusResponse,
    AdminUserStatusUpdateRequest,
    AdminVoucherIssueRequest,
    AdminVoucherIssueResponse,
    CustomerServiceNoteCreateRequest,
    CustomerServiceNoteResponse,
    RefundCaseCreateRequest,
    RefundCaseResponse,
    RefundCaseUpdateRequest,
    SystemConfigResponse,
    SystemConfigUpdateRequest,
)
from app.schemas.interviews import InterviewHistoryItem
from app.services.audit_logs import DatabaseAuditLogStore, InMemoryAuditLogStore, get_audit_log_store
from app.services.ai_call_logs import AICallLogStore, get_ai_call_log_store
from app.services.auth_sessions import AuthSessionStore, get_auth_session_store
from app.services.auth_login_logs import AuthLoginLogStore, get_auth_login_log_store
from app.services.content_safety_logs import ContentSafetyLogStore, get_content_safety_log_store
from app.services.admin_dashboard import build_admin_dashboard_stats
from app.services.admin_operations import (
    CreditBalanceCannotBeNegativeError,
    VoucherRecipientsRequiredError,
    VoucherUserDisabledError,
    VoucherUserNotFoundError,
    adjust_user_credits_with_stores,
    issue_vouchers_with_stores,
    normalize_user_email,
    resolve_voucher_recipients,
)
from app.services.credit_balances import CreditBalanceStore, DatabaseCreditBalanceStore, get_credit_balance_store
from app.services.credit_ledger import CreditLedgerStore, DatabaseCreditLedgerStore, get_credit_ledger_store
from app.services.customer_service_notes import CustomerServiceNoteStore, get_customer_service_note_store
from app.services.interview_vouchers import (
    DatabaseInterviewVoucherStore,
    InterviewVoucherStore,
    get_interview_voucher_store,
)
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
from app.services.refund_cases import RefundCaseNotFoundError, RefundCaseStore, get_refund_case_store
from app.services.user_credentials import UserAccountRecord, UserCredentialStore, UserNotFoundError, get_user_credential_store

router = APIRouter(prefix="/admin", tags=["admin"])
ADMIN_INTERVIEW_REQUIRED_TABLES = ("interview_sessions", "interview_turns", "interview_reports", "interview_materials")
ADMIN_USER_LIST_REQUIRED_TABLES = ("users", "interview_sessions")
ADMIN_CREDIT_ADJUSTMENT_REQUIRED_TABLES = ("users", "credit_ledger", "admin_audit_logs")
ADMIN_VOUCHER_REQUIRED_TABLES = ("users", "interview_vouchers", "admin_audit_logs")
ADMIN_STATS_REQUIRED_TABLES = (
    "users",
    "credit_ledger",
    "interview_sessions",
    "interview_reports",
    "ai_call_logs",
    "auth_login_logs",
    "refund_cases",
)


def get_optional_admin_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(("users",))


def get_optional_admin_user_list_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_USER_LIST_REQUIRED_TABLES)


def get_optional_admin_interview_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_INTERVIEW_REQUIRED_TABLES)


def get_optional_credit_adjustment_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_CREDIT_ADJUSTMENT_REQUIRED_TABLES)


def get_optional_voucher_issue_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_VOUCHER_REQUIRED_TABLES)


def get_optional_admin_stats_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_STATS_REQUIRED_TABLES)


def get_admin_interview_store(
    db_session: Session | None = Depends(get_optional_admin_interview_db_session),
) -> DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore:
    if db_session is None:
        return memory_interview_store
    return DatabaseInterviewRuntimeStore(db_session)


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


def summarize_database_users(users: list[User], db_session: Session) -> list[AdminUserSearchItem]:
    if not users:
        return []

    user_emails = [normalize_user_email(user.email) for user in users]
    completed_case = case((InterviewSession.status == "completed", 1), else_=0)
    usage_by_email = {
        normalize_user_email(user_email): {
            "total_interviews": int(total_interviews or 0),
            "completed_interviews": int(completed_interviews or 0),
            "last_interview_at": last_interview_at.isoformat() if last_interview_at else None,
        }
        for user_email, total_interviews, completed_interviews, last_interview_at in db_session.execute(
            select(
                InterviewSession.user_email,
                func.count(),
                func.coalesce(func.sum(completed_case), 0),
                func.max(InterviewSession.created_at),
            )
            .where(InterviewSession.user_email.in_(user_emails))
            .group_by(InterviewSession.user_email)
        ).all()
    }

    return [
        AdminUserSearchItem(
            email=normalize_user_email(user.email),
            role=user.role,
            is_active=user.is_active,
            credit_balance=int(user.credit_balance or 0),
            total_interviews=usage_by_email.get(normalize_user_email(user.email), {}).get("total_interviews", 0),
            completed_interviews=usage_by_email.get(normalize_user_email(user.email), {}).get("completed_interviews", 0),
            last_interview_at=usage_by_email.get(normalize_user_email(user.email), {}).get("last_interview_at"),
        )
        for user in users
    ]


def system_config_to_response(config: SystemConfig) -> SystemConfigResponse:
    return SystemConfigResponse(
        key=config.key,
        value=config.value,
        description=config.description,
        updated_at=config.updated_at.isoformat() if config.updated_at else None,
    )


def auth_login_log_to_response(log) -> AdminAuthLoginLogResponse:
    return AdminAuthLoginLogResponse(**log.__dict__)


def customer_service_note_to_response(note) -> CustomerServiceNoteResponse:
    return CustomerServiceNoteResponse(**note.__dict__)


def refund_case_to_response(refund_case) -> RefundCaseResponse:
    return RefundCaseResponse(**refund_case.__dict__)


def client_ip(request: Request) -> str | None:
    forwarded_for = request.headers.get("x-forwarded-for")
    if forwarded_for:
        return forwarded_for.split(",")[0].strip()
    return request.client.host if request.client else None


def search_database_users(query: str, db_session: Session | None, limit: int = 20) -> list[User]:
    if db_session is None:
        return []
    normalized_query = query.strip().lower()
    statement = select(User).order_by(User.created_at.desc(), User.email.asc()).limit(limit)
    if normalized_query:
        statement = (
            select(User)
            .where(User.email.ilike(f"%{normalized_query}%"))
            .order_by(User.created_at.desc(), User.email.asc())
            .limit(limit)
        )
    return list(db_session.execute(statement).scalars())


def list_database_users(query: str, db_session: Session | None, limit: int, offset: int) -> tuple[list[User], int]:
    if db_session is None:
        return [], 0
    normalized_query = query.strip().lower()
    filters = [User.email.ilike(f"%{normalized_query}%")] if normalized_query else []
    total = db_session.execute(select(func.count()).select_from(User).where(*filters)).scalar_one()
    users = list(
        db_session.execute(
            select(User).where(*filters).order_by(User.created_at.desc(), User.email.asc()).offset(offset).limit(limit)
        ).scalars()
    )
    return users, int(total)


@router.get("/stats", response_model=AdminDashboardStatsResponse)
def read_admin_dashboard_stats(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    db_session: Session | None = Depends(get_optional_admin_stats_db_session),
) -> AdminDashboardStatsResponse:
    return build_admin_dashboard_stats(db_session)


@router.get("/users/search", response_model=list[AdminUserSearchItem])
def search_users(
    query: str = Query(min_length=1, max_length=255),
    _admin_claims: TokenClaims = Depends(require_admin_user),
    credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_admin_interview_store),
    db_session: Session | None = Depends(get_optional_admin_user_list_db_session),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> list[AdminUserSearchItem]:
    normalized_query = normalize_user_email(query)
    database_users = search_database_users(normalized_query, db_session)
    if database_users:
        return summarize_database_users(database_users, db_session)

    user_records = user_store.search_users(normalized_query)
    if user_records:
        return [summarize_user_record(record, credit_store, interview_store) for record in user_records]

    return []


@router.get("/users", response_model=AdminUserListResponse)
def read_users(
    query: str | None = Query(default=None, max_length=255),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    _admin_claims: TokenClaims = Depends(require_admin_user),
    credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_admin_interview_store),
    db_session: Session | None = Depends(get_optional_admin_user_list_db_session),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> AdminUserListResponse:
    normalized_query = normalize_user_email(query or "")
    database_users, database_total = list_database_users(normalized_query, db_session, limit=limit, offset=offset)
    if db_session is not None:
        items = summarize_database_users(database_users, db_session)
        return AdminUserListResponse(
            items=items,
            total=database_total,
            limit=limit,
            offset=offset,
            has_more=offset + len(items) < database_total,
            total_is_estimated=False,
        )

    requested_count = offset + limit + 1
    user_records = user_store.search_users(normalized_query, limit=requested_count)
    page_records = user_records[offset : offset + limit]
    items = [summarize_user_record(record, credit_store, interview_store) for record in page_records]
    has_more = len(user_records) > offset + limit
    fallback_total_lower_bound = len(user_records) if not has_more else offset + limit + 1
    return AdminUserListResponse(
        items=items,
        total=fallback_total_lower_bound,
        limit=limit,
        offset=offset,
        has_more=has_more,
        total_is_estimated=has_more,
    )


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
    user_record = user_store.get_user_record(normalized_user_id)
    if user is None and user_record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found")

    role = user.role if user is not None else user_record.role
    is_active = user.is_active if user is not None else user_record.is_active
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
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> AdminUserStatusResponse:
    normalized_user_id = normalize_user_email(user_id)
    before_record = user_store.get_user_record(normalized_user_id)
    if before_record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found")
    if normalized_user_id == admin_claims["sub"] and not payload.is_active:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="cannot_disable_own_admin_account")
    before_active = before_record.is_active
    try:
        after_active = user_store.set_active(normalized_user_id, payload.is_active)
    except UserNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found") from exc
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
    if not after_active:
        auth_session_store.clear_session(normalized_user_id)
    return AdminUserStatusResponse(email=normalized_user_id, is_active=after_active)


@router.put("/users/{user_id}/role", response_model=AdminUserRoleResponse)
def update_user_role(
    request: Request,
    user_id: str,
    payload: AdminUserRoleUpdateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> AdminUserRoleResponse:
    normalized_user_id = normalize_user_email(user_id)
    before_record = user_store.get_user_record(normalized_user_id)
    if before_record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found")
    if normalized_user_id == admin_claims["sub"] and payload.role != "admin":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="cannot_change_own_admin_role")
    try:
        updated_record = user_store.set_role(normalized_user_id, payload.role)
    except UserNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found") from exc

    auth_session_store.clear_session(normalized_user_id)
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="user_role_update",
        target_type="user",
        target_id=normalized_user_id,
        before_snapshot={"role": before_record.role},
        after_snapshot={"role": updated_record.role, "reason": payload.reason},
        ip_address=client_ip(request),
        user_agent=request.headers.get("user-agent"),
    )
    return AdminUserRoleResponse(email=normalized_user_id, role=updated_record.role)


@router.post("/users/{user_id}/credits", response_model=AdminCreditAdjustmentResponse)
def adjust_user_credits(
    request: Request,
    user_id: str,
    payload: AdminCreditAdjustmentRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    fallback_credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    fallback_credit_ledger_store: CreditLedgerStore = Depends(get_credit_ledger_store),
    fallback_audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    adjustment_db_session: Session | None = Depends(get_optional_credit_adjustment_db_session),
) -> AdminCreditAdjustmentResponse:
    ip_address = request.client.host if request.client else None
    user_agent = request.headers.get("user-agent")
    if user_store.get_user_record(normalize_user_email(user_id)) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found")
    if adjustment_db_session is not None:
        try:
            response = adjust_user_credits_with_stores(
                user_id=user_id,
                payload=payload,
                admin_email=admin_claims["sub"],
                credit_store=DatabaseCreditBalanceStore(adjustment_db_session, commit_on_write=False),
                credit_ledger_store=DatabaseCreditLedgerStore(adjustment_db_session, commit_on_write=False),
                audit_store=DatabaseAuditLogStore(adjustment_db_session, commit_on_write=False),
                ip_address=ip_address,
                user_agent=user_agent,
            )
            adjustment_db_session.commit()
            return response
        except CreditBalanceCannotBeNegativeError as exc:
            adjustment_db_session.rollback()
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="credit_balance_cannot_be_negative") from exc
        except Exception:
            adjustment_db_session.rollback()
            raise

    try:
        return adjust_user_credits_with_stores(
            user_id=user_id,
            payload=payload,
            admin_email=admin_claims["sub"],
            credit_store=fallback_credit_store,
            credit_ledger_store=fallback_credit_ledger_store,
            audit_store=fallback_audit_store,
            ip_address=ip_address,
            user_agent=user_agent,
        )
    except CreditBalanceCannotBeNegativeError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="credit_balance_cannot_be_negative") from exc


@router.post("/vouchers", response_model=AdminVoucherIssueResponse, status_code=status.HTTP_201_CREATED)
def issue_vouchers(
    request: Request,
    payload: AdminVoucherIssueRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    fallback_voucher_store: InterviewVoucherStore = Depends(get_interview_voucher_store),
    fallback_audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    voucher_db_session: Session | None = Depends(get_optional_voucher_issue_db_session),
) -> AdminVoucherIssueResponse:
    try:
        target_emails = resolve_voucher_recipients(payload, user_store, voucher_db_session)
    except VoucherUserNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"user_not_found:{exc.email}") from exc
    except VoucherUserDisabledError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"user_disabled:{exc.email}") from exc
    except VoucherRecipientsRequiredError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="voucher_recipients_required") from exc

    ip_address = client_ip(request)
    user_agent = request.headers.get("user-agent")
    if voucher_db_session is not None:
        try:
            response = issue_vouchers_with_stores(
                payload=payload,
                admin_email=admin_claims["sub"],
                target_emails=target_emails,
                voucher_store=DatabaseInterviewVoucherStore(voucher_db_session, commit_on_write=False),
                audit_store=DatabaseAuditLogStore(voucher_db_session, commit_on_write=False),
                ip_address=ip_address,
                user_agent=user_agent,
            )
            voucher_db_session.commit()
            return response
        except VoucherRecipientsRequiredError as exc:
            voucher_db_session.rollback()
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="voucher_recipients_required") from exc
        except Exception:
            voucher_db_session.rollback()
            raise

    try:
        return issue_vouchers_with_stores(
            payload=payload,
            admin_email=admin_claims["sub"],
            target_emails=target_emails,
            voucher_store=fallback_voucher_store,
            audit_store=fallback_audit_store,
            ip_address=ip_address,
            user_agent=user_agent,
        )
    except VoucherRecipientsRequiredError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="voucher_recipients_required") from exc


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


@router.get("/auth-login-logs", response_model=list[AdminAuthLoginLogResponse])
def read_auth_login_logs(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    auth_login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
) -> list[AdminAuthLoginLogResponse]:
    return [auth_login_log_to_response(log) for log in auth_login_log_store.list_recent(limit=100)]


@router.get("/users/{user_id}/auth-login-logs", response_model=list[AdminAuthLoginLogResponse])
def read_user_auth_login_logs(
    user_id: str,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    auth_login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
) -> list[AdminAuthLoginLogResponse]:
    return [auth_login_log_to_response(log) for log in auth_login_log_store.list_for_user(user_id, limit=50)]


@router.get("/users/{user_id}/notes", response_model=list[CustomerServiceNoteResponse])
def read_user_customer_service_notes(
    user_id: str,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    note_store: CustomerServiceNoteStore = Depends(get_customer_service_note_store),
) -> list[CustomerServiceNoteResponse]:
    return [customer_service_note_to_response(note) for note in note_store.list_for_user(user_id, limit=80)]


@router.post("/users/{user_id}/notes", response_model=CustomerServiceNoteResponse, status_code=status.HTTP_201_CREATED)
def create_user_customer_service_note(
    request: Request,
    user_id: str,
    payload: CustomerServiceNoteCreateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    note_store: CustomerServiceNoteStore = Depends(get_customer_service_note_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
) -> CustomerServiceNoteResponse:
    normalized_user_id = normalize_user_email(user_id)
    if user_store.get_user_record(normalized_user_id) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found")
    note = note_store.create(
        user_email=normalized_user_id,
        admin_email=admin_claims["sub"],
        category=payload.category,
        content=payload.content,
        related_session_id=payload.related_session_id,
    )
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="customer_service_note_create",
        target_type="customer_service_note",
        target_id=note.id,
        after_snapshot={"user_email": normalized_user_id, "category": payload.category, "related_session_id": payload.related_session_id},
        ip_address=client_ip(request),
        user_agent=request.headers.get("user-agent"),
    )
    return customer_service_note_to_response(note)


@router.get("/refund-cases", response_model=list[RefundCaseResponse])
def read_refund_cases(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    refund_case_store: RefundCaseStore = Depends(get_refund_case_store),
) -> list[RefundCaseResponse]:
    return [refund_case_to_response(refund_case) for refund_case in refund_case_store.list_recent(limit=100)]


@router.get("/users/{user_id}/refund-cases", response_model=list[RefundCaseResponse])
def read_user_refund_cases(
    user_id: str,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    refund_case_store: RefundCaseStore = Depends(get_refund_case_store),
) -> list[RefundCaseResponse]:
    return [refund_case_to_response(refund_case) for refund_case in refund_case_store.list_for_user(user_id, limit=80)]


@router.post("/users/{user_id}/refund-cases", response_model=RefundCaseResponse, status_code=status.HTTP_201_CREATED)
def create_user_refund_case(
    request: Request,
    user_id: str,
    payload: RefundCaseCreateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    refund_case_store: RefundCaseStore = Depends(get_refund_case_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
) -> RefundCaseResponse:
    normalized_user_id = normalize_user_email(user_id)
    if user_store.get_user_record(normalized_user_id) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found")
    case = refund_case_store.create(
        user_email=normalized_user_id,
        reason=payload.reason,
        description=payload.description,
        amount_cents=payload.amount_cents,
        currency=payload.currency,
        credit_adjustment=payload.credit_adjustment,
        related_session_id=payload.related_session_id,
        created_by_admin_email=admin_claims["sub"],
    )
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="refund_case_create",
        target_type="refund_case",
        target_id=case.id,
        after_snapshot={"user_email": normalized_user_id, "reason": payload.reason, "status": case.status},
        ip_address=client_ip(request),
        user_agent=request.headers.get("user-agent"),
    )
    return refund_case_to_response(case)


@router.put("/refund-cases/{case_id}", response_model=RefundCaseResponse)
def update_refund_case(
    request: Request,
    case_id: str,
    payload: RefundCaseUpdateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    refund_case_store: RefundCaseStore = Depends(get_refund_case_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
) -> RefundCaseResponse:
    try:
        updated_case = refund_case_store.update(
            case_id=case_id,
            updated_by_admin_email=admin_claims["sub"],
            status=payload.status,
            resolution=payload.resolution,
            credit_adjustment=payload.credit_adjustment,
            amount_cents=payload.amount_cents,
        )
    except RefundCaseNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="refund_case_not_found") from exc
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="refund_case_update",
        target_type="refund_case",
        target_id=case_id,
        after_snapshot={"status": updated_case.status, "resolution": updated_case.resolution},
        ip_address=client_ip(request),
        user_agent=request.headers.get("user-agent"),
    )
    return refund_case_to_response(updated_case)


@router.get("/ai-call-logs", response_model=list[AdminAICallLogResponse])
def read_ai_call_logs(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    ai_call_log_store: AICallLogStore = Depends(get_ai_call_log_store),
) -> list[AdminAICallLogResponse]:
    return ai_call_log_store.list_recent(limit=80)


@router.get("/content-safety-logs", response_model=list[AdminContentSafetyLogResponse])
def read_content_safety_logs(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    content_safety_log_store: ContentSafetyLogStore = Depends(get_content_safety_log_store),
) -> list[AdminContentSafetyLogResponse]:
    return content_safety_log_store.list_recent(limit=80)


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
