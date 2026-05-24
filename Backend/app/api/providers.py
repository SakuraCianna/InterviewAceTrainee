from collections.abc import Generator

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.api.dependencies import TokenClaims, require_admin_user
from app.db.session import get_optional_db_session
from app.schemas.providers import (
    ProviderConfigCreateRequest,
    ProviderConfigUpdateRequest,
    ProviderConnectivityTestResponse,
    ProviderSelectionRequest,
    ProviderSelectionResponse,
)
from app.services.audit_logs import DatabaseAuditLogStore, InMemoryAuditLogStore, get_audit_log_store
from app.services.ai_router import AIProviderConfig, AIServiceRouter, NoProviderAvailableError
from app.services.llm_gateway import LLMProviderError, OpenAICompatibleLLMClient
from app.services.provider_configs import (
    DatabaseProviderConfigStore,
    InMemoryProviderConfigStore,
    ProviderConfigAlreadyExistsError,
    ProviderConfigNotFoundError,
    memory_provider_config_store,
)
from app.core.config import Settings, get_settings

router = APIRouter(prefix="/ai-providers", tags=["ai-providers"])
PROVIDER_AUDIT_REQUIRED_TABLES = ("ai_provider_configs", "admin_audit_logs")
CONFIG_VALIDATED_PROVIDER_TYPES = {"asr", "tts"}
CONFIG_VALIDATED_PROVIDERS = {"browser", "aliyun", "tencent", "volcengine", "iflytek", "baidu"}


def to_response(config: AIProviderConfig) -> ProviderSelectionResponse:
    return ProviderSelectionResponse(
        id=config.id,
        provider_type=config.provider_type,
        purpose=config.purpose,
        provider_name=config.provider_name,
        model_name=config.model_name,
        priority=config.priority,
        region=config.region,
        enabled=config.enabled,
        has_api_key=bool(config.api_key),
        api_key_preview=config.api_key_preview,
    )


def get_optional_provider_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(("ai_provider_configs",))


def get_optional_provider_audit_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(PROVIDER_AUDIT_REQUIRED_TABLES)


def get_provider_config_store(
    db_session: Session | None = Depends(get_optional_provider_db_session),
    settings: Settings = Depends(get_settings),
) -> DatabaseProviderConfigStore | InMemoryProviderConfigStore:
    if db_session is None:
        return memory_provider_config_store
    return DatabaseProviderConfigStore(db_session, settings=settings)


@router.get("", response_model=list[ProviderSelectionResponse])
def list_provider_configs(
    _admin_claims: TokenClaims = Depends(require_admin_user),
    store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
) -> list[ProviderSelectionResponse]:
    return [to_response(config) for config in store.list_configs()]


@router.post("", response_model=ProviderSelectionResponse, status_code=status.HTTP_201_CREATED)
def create_provider_config(
    request: Request,
    payload: ProviderConfigCreateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    fallback_store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
    fallback_audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
    provider_audit_db_session: Session | None = Depends(get_optional_provider_audit_db_session),
) -> ProviderSelectionResponse:
    ip_address = request.client.host if request.client else None
    user_agent = request.headers.get("user-agent")
    if provider_audit_db_session is not None:
        try:
            response = create_provider_config_with_stores(
                payload=payload,
                admin_claims=admin_claims,
                store=DatabaseProviderConfigStore(provider_audit_db_session, settings=get_settings(), commit_on_write=False),
                audit_store=DatabaseAuditLogStore(provider_audit_db_session, commit_on_write=False),
                ip_address=ip_address,
                user_agent=user_agent,
            )
            provider_audit_db_session.commit()
            return response
        except Exception:
            provider_audit_db_session.rollback()
            raise

    return create_provider_config_with_stores(
        payload=payload,
        admin_claims=admin_claims,
        store=fallback_store,
        audit_store=fallback_audit_store,
        ip_address=ip_address,
        user_agent=user_agent,
    )


def create_provider_config_with_stores(
    payload: ProviderConfigCreateRequest,
    admin_claims: TokenClaims,
    store: DatabaseProviderConfigStore | InMemoryProviderConfigStore,
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore,
    ip_address: str | None,
    user_agent: str | None,
) -> ProviderSelectionResponse:
    try:
        config = store.create_config(payload)
    except ProviderConfigAlreadyExistsError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="provider_config_already_exists") from exc
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="provider_create",
        target_type="ai_provider_config",
        target_id=config.id,
        after_snapshot=to_response(config).model_dump(mode="json"),
        ip_address=ip_address,
        user_agent=user_agent,
    )
    return to_response(config)


@router.put("/{provider_id}", response_model=ProviderSelectionResponse)
def update_provider_config(
    request: Request,
    provider_id: str,
    payload: ProviderConfigUpdateRequest,
    admin_claims: TokenClaims = Depends(require_admin_user),
    fallback_store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
    fallback_audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
    provider_audit_db_session: Session | None = Depends(get_optional_provider_audit_db_session),
) -> ProviderSelectionResponse:
    ip_address = request.client.host if request.client else None
    user_agent = request.headers.get("user-agent")
    if provider_audit_db_session is not None:
        try:
            response = update_provider_config_with_stores(
                provider_id=provider_id,
                payload=payload,
                admin_claims=admin_claims,
                store=DatabaseProviderConfigStore(provider_audit_db_session, settings=get_settings(), commit_on_write=False),
                audit_store=DatabaseAuditLogStore(provider_audit_db_session, commit_on_write=False),
                ip_address=ip_address,
                user_agent=user_agent,
            )
            provider_audit_db_session.commit()
            return response
        except Exception:
            provider_audit_db_session.rollback()
            raise

    return update_provider_config_with_stores(
        provider_id=provider_id,
        payload=payload,
        admin_claims=admin_claims,
        store=fallback_store,
        audit_store=fallback_audit_store,
        ip_address=ip_address,
        user_agent=user_agent,
    )


def update_provider_config_with_stores(
    provider_id: str,
    payload: ProviderConfigUpdateRequest,
    admin_claims: TokenClaims,
    store: DatabaseProviderConfigStore | InMemoryProviderConfigStore,
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore,
    ip_address: str | None,
    user_agent: str | None,
) -> ProviderSelectionResponse:
    try:
        before = next((config for config in store.list_configs() if config.id == provider_id), None)
        updated = store.update_config(provider_id, payload)
    except ProviderConfigNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="provider_config_not_found") from exc
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="provider_update",
        target_type="ai_provider_config",
        target_id=provider_id,
        before_snapshot=to_response(before).model_dump(mode="json") if before is not None else None,
        after_snapshot=to_response(updated).model_dump(mode="json"),
        ip_address=ip_address,
        user_agent=user_agent,
    )
    return to_response(updated)


@router.post("/select", response_model=ProviderSelectionResponse)
def select_provider(
    payload: ProviderSelectionRequest,
    _admin_claims: TokenClaims = Depends(require_admin_user),
    store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
) -> ProviderSelectionResponse:
    router_service = AIServiceRouter(store.list_configs())
    try:
        selected = router_service.select_provider(provider_type=payload.provider_type, purpose=payload.purpose)
    except NoProviderAvailableError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="provider_not_available") from exc

    return to_response(selected)


@router.post("/{provider_id}/test", response_model=ProviderConnectivityTestResponse)
def test_provider_connectivity(
    provider_id: str,
    request: Request,
    admin_claims: TokenClaims = Depends(require_admin_user),
    store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
    audit_store: DatabaseAuditLogStore | InMemoryAuditLogStore = Depends(get_audit_log_store),
    settings: Settings = Depends(get_settings),
) -> ProviderConnectivityTestResponse:
    try:
        config = store.get_config(provider_id)
    except ProviderConfigNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="provider_config_not_found") from exc

    result = run_connectivity_test(config, settings)
    audit_store.record(
        admin_email=admin_claims["sub"],
        action="provider_connectivity_test",
        target_type="ai_provider_config",
        target_id=provider_id,
        after_snapshot={"success": result.success, "detail": result.detail},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    return result


def run_connectivity_test(config: AIProviderConfig, settings: Settings) -> ProviderConnectivityTestResponse:
    if not config.enabled:
        return provider_test_response(config, success=False, detail="provider_disabled")

    if config.provider_type == "llm":
        try:
            OpenAICompatibleLLMClient(settings).complete(
                config,
                messages=[{"role": "user", "content": "Reply with ok only."}],
                temperature=0,
                max_tokens=8,
                timeout_seconds=8,
            )
        except LLMProviderError as exc:
            return provider_test_response(config, success=False, detail=str(exc))
        return provider_test_response(config, success=True, detail="llm_request_succeeded")

    if config.provider_type in CONFIG_VALIDATED_PROVIDER_TYPES:
        provider_name = config.provider_name.strip().lower()
        if provider_name in CONFIG_VALIDATED_PROVIDERS:
            return provider_test_response(config, success=True, detail="configuration_validated")
        return provider_test_response(config, success=False, detail="provider_not_supported")

    return provider_test_response(config, success=False, detail="provider_type_not_supported")


def provider_test_response(config: AIProviderConfig, success: bool, detail: str) -> ProviderConnectivityTestResponse:
    return ProviderConnectivityTestResponse(
        id=config.id,
        provider_type=config.provider_type,
        provider_name=config.provider_name,
        model_name=config.model_name,
        success=success,
        detail=detail,
    )
