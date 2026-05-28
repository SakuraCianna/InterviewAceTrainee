from collections.abc import Generator
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.api.dependencies import TokenClaims, require_admin_user
from app.db.session import get_optional_db_session
from app.models.entities import (
    AICallLog,
    AuthLoginLog,
    CreditLedgerModel,
    InterviewReport,
    InterviewSession,
    RefundCase,
    User,
)
from app.schemas.admin import (
    AdminAICallLogResponse,
    AdminAuthLoginLogResponse,
    AdminAuditLogResponse,
    AdminContentSafetyLogResponse,
    AdminCreditAdjustmentRequest,
    AdminCreditAdjustmentResponse,
    AdminCreditLedgerResponse,
    AdminDashboardOverview,
    AdminDashboardStatsResponse,
    AdminStatsPoint,
    AdminTopUserUsage,
    AdminUserDetailResponse,
    AdminUserInterviewReportResponse,
    AdminUserRoleResponse,
    AdminUserRoleUpdateRequest,
    AdminUserSearchItem,
    AdminUserStatusResponse,
    AdminUserStatusUpdateRequest,
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
from app.services.credit_balances import CreditBalanceStore, DatabaseCreditBalanceStore, get_credit_balance_store
from app.services.credit_ledger import CreditLedgerStore, DatabaseCreditLedgerStore, get_credit_ledger_store
from app.services.customer_service_notes import CustomerServiceNoteStore, get_customer_service_note_store
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
from app.services.refund_cases import RefundCaseNotFoundError, RefundCaseStore, get_refund_case_store
from app.services.user_credentials import UserAccountRecord, UserCredentialStore, UserNotFoundError, get_user_credential_store

router = APIRouter(prefix="/admin", tags=["admin"])
ADMIN_INTERVIEW_REQUIRED_TABLES = ("interview_sessions", "interview_turns", "interview_reports", "interview_materials")
ADMIN_CREDIT_ADJUSTMENT_REQUIRED_TABLES = ("users", "credit_ledger", "admin_audit_logs")
ADMIN_STATS_REQUIRED_TABLES = (
    "users",
    "credit_ledger",
    "interview_sessions",
    "interview_reports",
    "ai_call_logs",
    "auth_login_logs",
    "refund_cases",
)
INTERVIEW_TYPE_LABELS = {
    "job": "工作面试",
    "postgraduate": "研究生复试",
    "civil_service": "考公面试",
    "ielts": "雅思口语",
    "mixed": "综合模拟",
}
SESSION_STATUS_LABELS = {
    "created": "已创建",
    "in_progress": "进行中",
    "completed": "已完成",
    "failed": "异常",
}
REFUND_STATUS_LABELS = {
    "open": "待处理",
    "processing": "处理中",
    "resolved": "已解决",
    "rejected": "已驳回",
}


def get_optional_admin_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(("users",))


def get_optional_admin_interview_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_INTERVIEW_REQUIRED_TABLES)


def get_optional_credit_adjustment_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_CREDIT_ADJUSTMENT_REQUIRED_TABLES)


def get_optional_admin_stats_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(ADMIN_STATS_REQUIRED_TABLES)


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


def search_database_users(query: str, db_session: Session | None) -> list[User]:
    if db_session is None:
        return []
    normalized_query = f"%{query.strip().lower()}%"
    return list(
        db_session.execute(
            select(User).where(User.email.ilike(normalized_query)).order_by(User.created_at.desc()).limit(20)
        ).scalars()
    )


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def recent_day_labels(days: int = 14) -> list[str]:
    today = utc_now().date()
    return [(today - timedelta(days=offset)).isoformat() for offset in range(days - 1, -1, -1)]


def count_scalar(db_session: Session, statement) -> int:
    return int(db_session.execute(statement).scalar() or 0)


def optional_float_scalar(db_session: Session, statement) -> float | None:
    value = db_session.execute(statement).scalar()
    return None if value is None else round(float(value), 1)


def percentage(numerator: int, denominator: int) -> float | None:
    if denominator <= 0:
        return None
    return round(numerator / denominator * 100, 1)


def empty_dashboard_stats(database_ready: bool = False) -> AdminDashboardStatsResponse:
    return AdminDashboardStatsResponse(
        database_ready=database_ready,
        generated_at=utc_now().isoformat(),
        overview=AdminDashboardOverview(
            total_users=0,
            active_users=0,
            disabled_users=0,
            admin_users=0,
            total_credit_balance=0,
            total_credit_granted=0,
            total_sessions=0,
            completed_sessions=0,
            active_sessions=0,
            today_sessions=0,
            total_reports=0,
            average_report_score=None,
            ai_success_rate=None,
            failed_login_count=0,
            open_refund_cases=0,
        ),
        user_growth=[],
        daily_interviews=[],
        daily_reports=[],
        interview_type_distribution=[],
        session_status_distribution=[],
        ai_call_success_distribution=[],
        login_outcome_distribution=[],
        refund_status_distribution=[],
        top_users=[],
    )


def series_by_day(db_session: Session, column, labels: list[str]) -> list[AdminStatsPoint]:
    if not labels:
        return []

    start_date = datetime.fromisoformat(labels[0])
    day_expression = func.date(column)
    rows = db_session.execute(
        select(day_expression, func.count())
        .where(column >= start_date)
        .group_by(day_expression)
        .order_by(day_expression)
    ).all()
    value_by_day = {str(day): int(count) for day, count in rows}
    return [AdminStatsPoint(label=label[5:], value=value_by_day.get(label, 0)) for label in labels]


def distribution_by_column(db_session: Session, column, label_map: dict[str, str] | None = None) -> list[AdminStatsPoint]:
    rows = db_session.execute(
        select(column, func.count())
        .group_by(column)
        .order_by(func.count().desc())
    ).all()
    points: list[AdminStatsPoint] = []
    for raw_label, count in rows:
        label = str(raw_label or "unknown")
        points.append(AdminStatsPoint(label=label_map.get(label, label) if label_map else label, value=int(count)))
    return points


def read_top_users(db_session: Session, limit: int = 8) -> list[AdminTopUserUsage]:
    completed_case = case((InterviewSession.status == "completed", 1), else_=0)
    usage_rows = db_session.execute(
        select(
            InterviewSession.user_email,
            func.count(),
            func.coalesce(func.sum(completed_case), 0),
            func.max(InterviewSession.created_at),
        )
        .group_by(InterviewSession.user_email)
        .order_by(func.count().desc(), func.max(InterviewSession.created_at).desc())
        .limit(limit)
    ).all()
    if not usage_rows:
        return []

    emails = [row[0] for row in usage_rows]
    credit_by_email = {
        email: int(balance or 0)
        for email, balance in db_session.execute(
            select(User.email, User.credit_balance).where(User.email.in_(emails))
        ).all()
    }
    return [
        AdminTopUserUsage(
            email=email,
            total_interviews=int(total_interviews or 0),
            completed_interviews=int(completed_interviews or 0),
            credit_balance=credit_by_email.get(email, 0),
            last_interview_at=last_interview_at.isoformat() if last_interview_at else None,
        )
        for email, total_interviews, completed_interviews, last_interview_at in usage_rows
    ]


@router.get("/stats", response_model=AdminDashboardStatsResponse)
def read_admin_dashboard_stats(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    db_session: Session | None = Depends(get_optional_admin_stats_db_session),
) -> AdminDashboardStatsResponse:
    if db_session is None:
        return empty_dashboard_stats(database_ready=False)

    labels = recent_day_labels()
    today_start = datetime.combine(utc_now().date(), datetime.min.time())
    total_users = count_scalar(db_session, select(func.count()).select_from(User))
    active_users = count_scalar(db_session, select(func.count()).select_from(User).where(User.is_active.is_(True)))
    disabled_users = count_scalar(db_session, select(func.count()).select_from(User).where(User.is_active.is_(False)))
    admin_users = count_scalar(db_session, select(func.count()).select_from(User).where(User.role == "admin"))
    total_credit_balance = count_scalar(db_session, select(func.coalesce(func.sum(User.credit_balance), 0)))
    total_credit_granted = count_scalar(
        db_session,
        select(func.coalesce(func.sum(case((CreditLedgerModel.change_amount > 0, CreditLedgerModel.change_amount), else_=0)), 0)),
    )
    total_sessions = count_scalar(db_session, select(func.count()).select_from(InterviewSession))
    completed_sessions = count_scalar(
        db_session,
        select(func.count()).select_from(InterviewSession).where(InterviewSession.status == "completed"),
    )
    active_sessions = count_scalar(
        db_session,
        select(func.count()).select_from(InterviewSession).where(InterviewSession.status.in_(("created", "in_progress"))),
    )
    today_sessions = count_scalar(
        db_session,
        select(func.count()).select_from(InterviewSession).where(InterviewSession.created_at >= today_start),
    )
    total_reports = count_scalar(db_session, select(func.count()).select_from(InterviewReport))
    ai_total = count_scalar(db_session, select(func.count()).select_from(AICallLog))
    ai_success = count_scalar(db_session, select(func.count()).select_from(AICallLog).where(AICallLog.success.is_(True)))
    login_success = count_scalar(db_session, select(func.count()).select_from(AuthLoginLog).where(AuthLoginLog.success.is_(True)))
    login_failed = count_scalar(db_session, select(func.count()).select_from(AuthLoginLog).where(AuthLoginLog.success.is_(False)))
    open_refund_cases = count_scalar(
        db_session,
        select(func.count()).select_from(RefundCase).where(RefundCase.status.in_(("open", "processing"))),
    )

    return AdminDashboardStatsResponse(
        database_ready=True,
        generated_at=utc_now().isoformat(),
        overview=AdminDashboardOverview(
            total_users=total_users,
            active_users=active_users,
            disabled_users=disabled_users,
            admin_users=admin_users,
            total_credit_balance=total_credit_balance,
            total_credit_granted=total_credit_granted,
            total_sessions=total_sessions,
            completed_sessions=completed_sessions,
            active_sessions=active_sessions,
            today_sessions=today_sessions,
            total_reports=total_reports,
            average_report_score=optional_float_scalar(db_session, select(func.avg(InterviewReport.total_score))),
            ai_success_rate=percentage(ai_success, ai_total),
            failed_login_count=login_failed,
            open_refund_cases=open_refund_cases,
        ),
        user_growth=series_by_day(db_session, User.created_at, labels),
        daily_interviews=series_by_day(db_session, InterviewSession.created_at, labels),
        daily_reports=series_by_day(db_session, InterviewReport.created_at, labels),
        interview_type_distribution=distribution_by_column(db_session, InterviewSession.interview_type, INTERVIEW_TYPE_LABELS),
        session_status_distribution=distribution_by_column(db_session, InterviewSession.status, SESSION_STATUS_LABELS),
        ai_call_success_distribution=[
            AdminStatsPoint(label="成功", value=ai_success),
            AdminStatsPoint(label="失败", value=max(ai_total - ai_success, 0)),
        ],
        login_outcome_distribution=[
            AdminStatsPoint(label="成功", value=login_success),
            AdminStatsPoint(label="失败", value=login_failed),
        ],
        refund_status_distribution=distribution_by_column(db_session, RefundCase.status, REFUND_STATUS_LABELS),
        top_users=read_top_users(db_session),
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

    return []


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
