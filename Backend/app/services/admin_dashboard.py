from datetime import datetime, timedelta, timezone
from threading import RLock
from time import monotonic

from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.models.entities import AICallLog, AuthLoginLog, CreditLedgerModel, InterviewReport, InterviewSession, RefundCase, User
from app.schemas.admin import AdminDashboardOverview, AdminDashboardStatsResponse, AdminStatsPoint, AdminTopUserUsage

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
_ADMIN_DASHBOARD_STATS_CACHE_SECONDS = 10.0
_admin_dashboard_stats_cache: tuple[AdminDashboardStatsResponse, float] | None = None
_admin_dashboard_stats_cache_lock = RLock()


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


def build_admin_dashboard_stats(db_session: Session | None) -> AdminDashboardStatsResponse:
    if db_session is None:
        return empty_dashboard_stats(database_ready=False)

    global _admin_dashboard_stats_cache
    now = monotonic()
    with _admin_dashboard_stats_cache_lock:
        if _admin_dashboard_stats_cache is not None and _admin_dashboard_stats_cache[1] > now:
            return _admin_dashboard_stats_cache[0].model_copy(deep=True)

    payload = _build_admin_dashboard_stats_uncached(db_session)
    with _admin_dashboard_stats_cache_lock:
        _admin_dashboard_stats_cache = (payload.model_copy(deep=True), monotonic() + _ADMIN_DASHBOARD_STATS_CACHE_SECONDS)
    return payload.model_copy(deep=True)


def clear_admin_dashboard_stats_cache() -> None:
    global _admin_dashboard_stats_cache
    with _admin_dashboard_stats_cache_lock:
        _admin_dashboard_stats_cache = None


def _build_admin_dashboard_stats_uncached(db_session: Session) -> AdminDashboardStatsResponse:
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
