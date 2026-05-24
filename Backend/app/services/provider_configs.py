from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.entities import AIProviderConfigModel
from app.schemas.providers import ProviderConfigCreateRequest, ProviderConfigUpdateRequest
from app.services.ai_router import AIProviderConfig


class ProviderConfigAlreadyExistsError(ValueError):
    """Raised when an AI provider config id already exists."""


class ProviderConfigNotFoundError(LookupError):
    """Raised when an AI provider config cannot be found."""


def default_provider_configs() -> list[AIProviderConfig]:
    return [
        AIProviderConfig(
            id="glm-4.7-flash",
            provider_type="llm",
            purpose="general",
            enabled=True,
            priority=10,
            provider_name="zhipu",
            model_name="GLM-4.7-Flash",
            region="cn",
        ),
        AIProviderConfig(
            id="glm-z1-flash",
            provider_type="llm",
            purpose="general",
            enabled=True,
            priority=12,
            provider_name="zhipu",
            model_name="glm-z1-flash",
            region="cn",
        ),
        AIProviderConfig(
            id="glm-4-flash-250414",
            provider_type="llm",
            purpose="general",
            enabled=True,
            priority=15,
            provider_name="zhipu",
            model_name="glm-4-flash-250414",
            region="cn",
        ),
        AIProviderConfig(
            id="qwen-flash",
            provider_type="llm",
            purpose="general",
            enabled=True,
            priority=20,
            provider_name="aliyun-bailian",
            model_name="qwen-flash",
            region="cn",
        ),
        AIProviderConfig(
            id="doubao-seed-1.6-flash",
            provider_type="llm",
            purpose="general",
            enabled=True,
            priority=30,
            provider_name="volcengine-ark",
            model_name="doubao-seed-1.6-flash",
            region="cn",
        ),
        AIProviderConfig(
            id="deepseek-v4-flash",
            provider_type="llm",
            purpose="general",
            enabled=True,
            priority=90,
            provider_name="deepseek",
            model_name="deepseek-v4-flash",
            region="cn",
        ),
    ]


class InMemoryProviderConfigStore:
    def __init__(self, configs: list[AIProviderConfig] | None = None) -> None:
        self._configs = list(configs or default_provider_configs())

    def list_configs(self) -> list[AIProviderConfig]:
        return sorted(self._configs, key=lambda config: config.priority)

    def create_config(self, payload: ProviderConfigCreateRequest) -> AIProviderConfig:
        if any(config.id == payload.id for config in self._configs):
            raise ProviderConfigAlreadyExistsError("provider config already exists")
        config = AIProviderConfig(
            id=payload.id,
            provider_type=payload.provider_type,
            purpose=payload.purpose,
            enabled=payload.enabled,
            priority=payload.priority,
            provider_name=payload.provider_name,
            model_name=payload.model_name,
            region=payload.region,
        )
        self._configs.append(config)
        return config

    def update_config(self, provider_id: str, payload: ProviderConfigUpdateRequest) -> AIProviderConfig:
        for index, config in enumerate(self._configs):
            if config.id == provider_id:
                updated = AIProviderConfig(
                    id=config.id,
                    provider_type=payload.provider_type or config.provider_type,
                    purpose=payload.purpose or config.purpose,
                    enabled=config.enabled if payload.enabled is None else payload.enabled,
                    priority=config.priority if payload.priority is None else payload.priority,
                    provider_name=payload.provider_name or config.provider_name,
                    model_name=payload.model_name or config.model_name,
                    region=payload.region or config.region,
                )
                self._configs[index] = updated
                return updated
        raise ProviderConfigNotFoundError("provider config not found")


class DatabaseProviderConfigStore:
    def __init__(self, session: Session) -> None:
        self._session = session

    def list_configs(self) -> list[AIProviderConfig]:
        self._seed_defaults_if_empty()
        models = self._session.execute(select(AIProviderConfigModel).order_by(AIProviderConfigModel.priority)).scalars()
        return [self._to_config(model) for model in models]

    def create_config(self, payload: ProviderConfigCreateRequest) -> AIProviderConfig:
        if self._get_model(payload.id) is not None:
            raise ProviderConfigAlreadyExistsError("provider config already exists")
        model = AIProviderConfigModel(
            id=payload.id,
            provider_type=payload.provider_type,
            purpose=payload.purpose,
            enabled=payload.enabled,
            priority=payload.priority,
            provider_name=payload.provider_name,
            display_name=payload.provider_name,
            model_name=payload.model_name,
            region=payload.region,
        )
        self._session.add(model)
        self._session.commit()
        return self._to_config(model)

    def update_config(self, provider_id: str, payload: ProviderConfigUpdateRequest) -> AIProviderConfig:
        model = self._get_model(provider_id)
        if model is None:
            raise ProviderConfigNotFoundError("provider config not found")
        if payload.provider_type is not None:
            model.provider_type = payload.provider_type
        if payload.purpose is not None:
            model.purpose = payload.purpose
        if payload.provider_name is not None:
            model.provider_name = payload.provider_name
            model.display_name = payload.provider_name
        if payload.model_name is not None:
            model.model_name = payload.model_name
        if payload.priority is not None:
            model.priority = payload.priority
        if payload.region is not None:
            model.region = payload.region
        if payload.enabled is not None:
            model.enabled = payload.enabled
        self._session.commit()
        return self._to_config(model)

    def _seed_defaults_if_empty(self) -> None:
        has_any = self._session.execute(select(AIProviderConfigModel.id).limit(1)).scalar_one_or_none()
        if has_any is not None:
            return
        for config in default_provider_configs():
            self._session.add(
                AIProviderConfigModel(
                    id=config.id,
                    provider_type=config.provider_type,
                    purpose=config.purpose,
                    enabled=config.enabled,
                    priority=config.priority,
                    provider_name=config.provider_name,
                    display_name=config.provider_name,
                    model_name=config.model_name,
                    region=config.region,
                )
            )
        self._session.commit()

    def _get_model(self, provider_id: str) -> AIProviderConfigModel | None:
        return self._session.get(AIProviderConfigModel, provider_id)

    def _to_config(self, model: AIProviderConfigModel) -> AIProviderConfig:
        return AIProviderConfig(
            id=model.id,
            provider_type=model.provider_type,
            purpose=model.purpose,
            enabled=model.enabled,
            priority=model.priority,
            provider_name=model.provider_name,
            model_name=model.model_name,
            region=model.region,
        )


memory_provider_config_store = InMemoryProviderConfigStore()
