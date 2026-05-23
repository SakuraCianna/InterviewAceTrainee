from fastapi import APIRouter, HTTPException, status

from app.schemas.providers import ProviderSelectionRequest, ProviderSelectionResponse
from app.services.ai_router import AIProviderConfig, AIServiceRouter, NoProviderAvailableError

router = APIRouter(prefix="/ai-providers", tags=["ai-providers"])

_provider_configs: list[AIProviderConfig] = [
    AIProviderConfig(
        id="glm-4.7-flash",
        provider_type="llm",
        purpose="general",
        enabled=True,
        priority=10,
        provider_name="zhipu",
        model_name="GLM-4.7-Flash",
        region="cn",
    )
]


@router.get("", response_model=list[ProviderSelectionResponse])
def list_provider_configs() -> list[ProviderSelectionResponse]:
    return [
        ProviderSelectionResponse(
            id=config.id,
            provider_type=config.provider_type,
            purpose=config.purpose,
            provider_name=config.provider_name,
            model_name=config.model_name,
            priority=config.priority,
            region=config.region,
        )
        for config in _provider_configs
    ]


@router.post("/select", response_model=ProviderSelectionResponse)
def select_provider(payload: ProviderSelectionRequest) -> ProviderSelectionResponse:
    router_service = AIServiceRouter(_provider_configs)
    try:
        selected = router_service.select_provider(provider_type=payload.provider_type, purpose=payload.purpose)
    except NoProviderAvailableError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="provider_not_available") from exc

    return ProviderSelectionResponse(
        id=selected.id,
        provider_type=selected.provider_type,
        purpose=selected.purpose,
        provider_name=selected.provider_name,
        model_name=selected.model_name,
        priority=selected.priority,
        region=selected.region,
    )

