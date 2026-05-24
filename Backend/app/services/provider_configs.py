from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.models.entities import AIProviderConfigModel
from app.schemas.providers import ProviderConfigUpdateRequest
from app.services.ai_router import AIProviderConfig
from app.services.secret_box import decrypt_secret, encrypt_secret, preview_secret

PRIMARY_LLM_PROVIDER_ID = "deepseek-v4-flash"
PRIMARY_ASR_PROVIDER_ID = "tencent-asr-realtime"
PRIMARY_TTS_PROVIDER_ID = "tencent-tts-online"
ACTIVE_PROVIDER_IDS = {PRIMARY_LLM_PROVIDER_ID, PRIMARY_ASR_PROVIDER_ID, PRIMARY_TTS_PROVIDER_ID}


class ProviderConfigNotFoundError(LookupError):
    """Raised when an AI provider config cannot be found."""


def default_provider_configs() -> list[AIProviderConfig]:
    return [
        AIProviderConfig(
            id=PRIMARY_LLM_PROVIDER_ID,
            provider_type="llm",
            purpose="general",
            enabled=True,
            priority=10,
            provider_name="deepseek",
            model_name="deepseek-v4-flash",
            region="cn",
        ),
        AIProviderConfig(
            id=PRIMARY_ASR_PROVIDER_ID,
            provider_type="asr",
            purpose="interview",
            enabled=True,
            priority=10,
            provider_name="tencent",
            model_name="16k_zh",
            region="cn",
        ),
        AIProviderConfig(
            id=PRIMARY_TTS_PROVIDER_ID,
            provider_type="tts",
            purpose="interview",
            enabled=True,
            priority=10,
            provider_name="tencent",
            model_name="tencent-cloud-tts",
            region="cn",
        ),
    ]


class InMemoryProviderConfigStore:
    def __init__(self, configs: list[AIProviderConfig] | None = None) -> None:
        self._configs = list(configs or default_provider_configs())

    def list_configs(self) -> list[AIProviderConfig]:
        return sorted(self._configs, key=lambda config: config.priority)

    def update_config(self, provider_id: str, payload: ProviderConfigUpdateRequest) -> AIProviderConfig:
        for index, config in enumerate(self._configs):
            if config.id == provider_id:
                updated = AIProviderConfig(
                    id=config.id,
                    provider_type=config.provider_type,
                    purpose=config.purpose,
                    enabled=config.enabled if payload.enabled is None else payload.enabled,
                    priority=config.priority if payload.priority is None else payload.priority,
                    provider_name=config.provider_name,
                    model_name=config.model_name,
                    region=config.region,
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
        self._prune_unsupported_provider_configs()
        self._seed_missing_default_configs()
        models = self._session.execute(select(AIProviderConfigModel).order_by(AIProviderConfigModel.priority)).scalars()
        return [self._to_config(model) for model in models]

    def update_config(self, provider_id: str, payload: ProviderConfigUpdateRequest) -> AIProviderConfig:
        model = self._get_model(provider_id)
        if model is None:
            raise ProviderConfigNotFoundError("provider config not found")
        if payload.priority is not None:
            model.priority = payload.priority
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

    def _seed_missing_default_configs(self) -> None:
        existing_ids = set(self._session.execute(select(AIProviderConfigModel.id)).scalars().all())
        for config in default_provider_configs():
            if config.id in existing_ids:
                continue
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

    def _prune_unsupported_provider_configs(self) -> None:
        removed_models = (
            self._session.execute(
                select(AIProviderConfigModel).where(
                    ~AIProviderConfigModel.id.in_(ACTIVE_PROVIDER_IDS),
                )
            )
            .scalars()
            .all()
        )
        if not removed_models:
            return
        for model in removed_models:
            self._session.delete(model)
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
