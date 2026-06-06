from fastapi import APIRouter
from sqlalchemy import inspect
from sqlalchemy.exc import SQLAlchemyError

from app.core.config import get_settings
from app.db.session import engine
from app.services.interview_quality_observer import observe_interview_core_readiness
from app.services.redis_runtime import get_redis_client

router = APIRouter(prefix="/health", tags=["health"])

REQUIRED_TABLES = (
    "users",
    "credit_ledger",
    "interview_vouchers",
    "interview_sessions",
    "interview_materials",
    "interview_turns",
    "interview_reports",
    "ai_provider_configs",
    "ai_call_logs",
    "content_safety_logs",
    "auth_login_logs",
    "customer_service_notes",
    "refund_cases",
    "admin_audit_logs",
    "system_configs",
)


@router.get("")
def read_health() -> dict[str, str]:
    return {"status": "ok", "service": "mianba-backend"}


@router.get("/readiness")
def read_readiness() -> dict[str, object]:
    settings = get_settings()
    checks = {
        "database": check_database(),
        "redis": check_redis(),
        "email": check_email(settings),
        "auth": check_auth(settings),
        "interview_core": check_interview_core(),
    }
    required_checks_ready = all(
        checks[name]["ready"]
        for name in ("database", "auth", "interview_core")
    )
    status = "ready" if required_checks_ready else "degraded"
    return {"status": status, "service": "mianba-backend", "checks": checks}


@router.get("/interview-core")
def read_interview_core_readiness() -> dict[str, object]:
    return check_interview_core()


def check_database() -> dict[str, object]:
    try:
        inspector = inspect(engine)
        missing_tables = [table for table in REQUIRED_TABLES if not inspector.has_table(table)]
    except SQLAlchemyError as exc:
        return {"ready": False, "detail": str(exc)}

    return {"ready": not missing_tables, "missing_tables": missing_tables}


def check_redis() -> dict[str, object]:
    client = get_redis_client()
    return {"ready": client is not None}


def check_email(settings) -> dict[str, object]:
    provider = settings.email_provider.strip().lower()
    domestic_provider = settings.domestic_email_provider.strip().lower()
    domestic_ready = True
    domestic_detail: dict[str, object] | None = None
    if domestic_provider == "sendcloud":
        domestic_ready = bool(settings.sendcloud_api_user and settings.sendcloud_api_key and (settings.sendcloud_from_address or settings.email_from_address))
        domestic_detail = {
            "provider": domestic_provider,
            "ready": domestic_ready,
            "from_address": settings.sendcloud_from_address or settings.email_from_address,
        }
    elif domestic_provider:
        domestic_ready = False
        domestic_detail = {"provider": domestic_provider, "ready": False, "detail": "unsupported_domestic_email_provider"}

    if provider == "dev":
        return {"ready": domestic_ready, "provider": provider, "detail": "dev_code_response_enabled", "domestic": domestic_detail}
    if provider == "resend":
        return {
            "ready": bool(settings.resend_api_key and settings.email_from_address) and domestic_ready,
            "provider": provider,
            "from_address": settings.email_from_address,
            "domestic": domestic_detail,
        }
    return {"ready": False, "provider": provider, "detail": "unsupported_email_provider", "domestic": domestic_detail}


def check_auth(settings) -> dict[str, object]:
    return {
        "ready": settings.access_token_secret != "change-me-before-deploy" and len(settings.access_token_secret) >= 32,
        "cookie_secure": settings.auth_cookie_secure,
        "same_site": settings.auth_cookie_samesite,
    }


def check_interview_core() -> dict[str, object]:
    try:
        with engine.connect() as connection:
            return observe_interview_core_readiness(connection).to_dict()
    except SQLAlchemyError as exc:
        return observe_interview_core_readiness(database_error=str(exc)).to_dict()
