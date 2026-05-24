from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.models.entities import AIProviderConfigModel
from app.schemas.providers import ProviderConfigCreateRequest, ProviderConfigUpdateRequest
from app.services.ai_router import AIProviderConfig
from app.services.secret_box import decrypt_secret, encrypt_secret, preview_secret


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
        AIProviderConfig(
            id="browser-asr",
            provider_type="asr",
            purpose="interview",
            enabled=True,
            priority=10,
            provider_name="browser",
            model_name="web-speech-recognition",
            region="cn",
        ),
        AIProviderConfig(
            id="aliyun-asr-flash",
            provider_type="asr",
            purpose="interview",
            enabled=False,
            priority=20,
            provider_name="aliyun",
            model_name="paraformer-realtime-v2",
            region="cn",
        ),
        AIProviderConfig(
            id="browser-tts",
            provider_type="tts",
            purpose="interview",
            enabled=True,
            priority=10,
            provider_name="browser",
            model_name="web-speech-synthesis",
            region="cn",
        ),
        AIProviderConfig(
            id="volcengine-tts-flash",
            provider_type="tts",
            purpose="interview",
            enabled=False,
            priority=20,
            provider_name="volcengine",
            model_name="seed-tts",
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
            api_key=payload.api_key or "",
            api_key_preview=preview_secret(payload.api_key),
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
                    api_key=config.api_key if payload.api_key is None else payload.api_key,
                    api_key_preview=config.api_key_preview if payload.api_key is None else preview_secret(payload.api_key),
                )
                self._configs[index] = updated
                return updated
        raise ProviderConfigNotFoundError("provider config not found")

    def get_config(self, provider_id: str) -> AIProviderConfig:
        for config in self._configs:
            if config.id == provider_id:
                return config
        raise ProviderConfigNotFoundError("provider config not found")


class DatabaseProviderConfigStore:
    def __init__(self, session: Session, settings: Settings | None = None, commit_on_write: bool = True) -> None:
        self._session = session
        self._settings = settings or get_settings()
        self._commit_on_write = commit_on_write

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
            encrypted_api_key=encrypt_secret(payload.api_key, self._settings),
        )
        self._session.add(model)
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
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
        if payload.api_key is not None:
            model.encrypted_api_key = encrypt_secret(payload.api_key, self._settings)
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_config(model)

    def get_config(self, provider_id: str) -> AIProviderConfig:
        model = self._get_model(provider_id)
        if model is None:
            raise ProviderConfigNotFoundError("provider config not found")
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
                    encrypted_api_key=encrypt_secret(config.api_key, self._settings),
                )
            )
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()

    def _get_model(self, provider_id: str) -> AIProviderConfigModel | None:
        return self._session.get(AIProviderConfigModel, provider_id)

    def _to_config(self, model: AIProviderConfigModel) -> AIProviderConfig:
        api_key = decrypt_secret(model.encrypted_api_key, self._settings)
        return AIProviderConfig(
            id=model.id,
            provider_type=model.provider_type,
            purpose=model.purpose,
            enabled=model.enabled,
            priority=model.priority,
            provider_name=model.provider_name,
            model_name=model.model_name,
            region=model.region,
            api_key=api_key,
            api_key_preview=preview_secret(api_key),
        )


memory_provider_config_store = InMemoryProviderConfigStore()
