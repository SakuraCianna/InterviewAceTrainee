from dataclasses import dataclass
from datetime import datetime
from typing import Any

from fastapi import Depends
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import SystemConfigModel, utc_now
from app.services.provider_configs import PRIMARY_ASR_PROVIDER_ID, PRIMARY_LLM_PROVIDER_ID, PRIMARY_TTS_PROVIDER_ID


class SystemConfigNotFoundError(LookupError):
    """Raised when a system config key is unknown."""


@dataclass(frozen=True)
class SystemConfig:
    key: str
    value: Any
    description: str
    updated_at: datetime | None = None


DEFAULT_SYSTEM_CONFIGS: dict[str, SystemConfig] = {
    "registration_open": SystemConfig("registration_open", True, "是否开放新用户注册"),
    "new_user_default_credits": SystemConfig("new_user_default_credits", 0, "新用户默认赠送训练次数"),
    "password_login_enabled": SystemConfig("password_login_enabled", True, "是否允许普通用户密码登录"),
    "email_code_login_enabled": SystemConfig("email_code_login_enabled", True, "是否允许普通用户邮箱验证码登录"),
    "max_interview_steps": SystemConfig("max_interview_steps", 8, "单场训练最大轮数上限"),
    "max_answer_seconds": SystemConfig("max_answer_seconds", 180, "单轮回答建议最长秒数"),
    "default_llm_provider_id": SystemConfig("default_llm_provider_id", PRIMARY_LLM_PROVIDER_ID, "默认 LLM 配置 ID"),
    "default_asr_provider_id": SystemConfig("default_asr_provider_id", PRIMARY_ASR_PROVIDER_ID, "默认 ASR 配置 ID"),
    "default_tts_provider_id": SystemConfig("default_tts_provider_id", PRIMARY_TTS_PROVIDER_ID, "默认 TTS 配置 ID"),
}

PROVIDER_DEFAULT_CONFIG_KEYS = {"default_llm_provider_id", "default_asr_provider_id", "default_tts_provider_id"}


class InMemorySystemConfigStore:
    def __init__(self) -> None:
        self._configs = dict(DEFAULT_SYSTEM_CONFIGS)

    def list_configs(self) -> list[SystemConfig]:
        return sorted(self._configs.values(), key=lambda config: config.key)

    def get(self, key: str) -> SystemConfig:
        try:
            return self._configs[key]
        except KeyError as exc:
            raise SystemConfigNotFoundError("system config not found") from exc

    def update(self, key: str, value: Any, description: str | None = None) -> SystemConfig:
        current = self.get(key)
        updated = SystemConfig(
            key=key,
            value=value,
            description=current.description if description is None else description,
            updated_at=utc_now(),
        )
        self._configs[key] = updated
        return updated

    def get_bool(self, key: str) -> bool:
        return bool(self.get(key).value)

    def get_int(self, key: str) -> int:
        return int(self.get(key).value)


class DatabaseSystemConfigStore:
    def __init__(self, session: Session, commit_on_write: bool = True) -> None:
        self._session = session
        self._commit_on_write = commit_on_write

    def list_configs(self) -> list[SystemConfig]:
        self._seed_defaults()
        models = self._session.query(SystemConfigModel).order_by(SystemConfigModel.key).all()
        return [self._to_config(model) for model in models]

    def get(self, key: str) -> SystemConfig:
        self._seed_defaults()
        model = self._session.get(SystemConfigModel, key)
        if model is None:
            raise SystemConfigNotFoundError("system config not found")
        return self._to_config(model)

    def update(self, key: str, value: Any, description: str | None = None) -> SystemConfig:
        self._seed_defaults()
        model = self._session.get(SystemConfigModel, key)
        if model is None:
            raise SystemConfigNotFoundError("system config not found")
        model.value_json = value
        if description is not None:
            model.description = description
        model.updated_at = utc_now()
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
        return self._to_config(model)

    def get_bool(self, key: str) -> bool:
        return bool(self.get(key).value)

    def get_int(self, key: str) -> int:
        return int(self.get(key).value)

    def _seed_defaults(self) -> None:
        changed = False
        for config in DEFAULT_SYSTEM_CONFIGS.values():
            model = self._session.get(SystemConfigModel, config.key)
            if model is not None:
                if config.key in PROVIDER_DEFAULT_CONFIG_KEYS and model.value_json != config.value:
                    model.value_json = config.value
                    model.description = config.description
                    model.updated_at = utc_now()
                    changed = True
                continue
            self._session.add(
                SystemConfigModel(
                    key=config.key,
                    value_json=config.value,
                    description=config.description,
                    updated_at=utc_now(),
                )
            )
            changed = True
        if changed:
            if self._commit_on_write:
                self._session.commit()
            else:
                self._session.flush()

    def _to_config(self, model: SystemConfigModel) -> SystemConfig:
        return SystemConfig(
            key=model.key,
            value=model.value_json,
            description=model.description,
            updated_at=model.updated_at,
        )


memory_system_config_store = InMemorySystemConfigStore()


def get_optional_system_config_db_session():
    yield from get_optional_db_session(("system_configs",))


def get_system_config_store(
    db_session: Session | None = Depends(get_optional_system_config_db_session),
) -> DatabaseSystemConfigStore | InMemorySystemConfigStore:
    if db_session is None:
        return memory_system_config_store
    return DatabaseSystemConfigStore(db_session)
