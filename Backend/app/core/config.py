from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "面霸练习生"
    api_prefix: str = "/api"
    environment: str = "development"
    database_url: str = Field(default="postgresql+psycopg://mianba:mianba@postgres:5432/mianba")
    redis_url: str = Field(default="redis://redis:6379/0")
    admin_entry_path: str = "/console-mianba"
    access_token_secret: str = "change-me-before-production"
    password_hash_target_ms: int = 200

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()

