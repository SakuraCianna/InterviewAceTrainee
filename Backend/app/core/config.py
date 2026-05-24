from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "面霸练习生"
    api_prefix: str = "/api"
    environment: str = "runtime"
    database_url: str = Field(default="postgresql+psycopg://mianba:mianba@postgres:5432/mianba")
    redis_url: str = Field(default="redis://redis:6379/0")
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173,https://sakuracianna.icu,https://www.sakuracianna.icu"
    admin_entry_path: str = "/console-mianba"
    admin_email_allowlist: str = ""
    auth_cookie_secure: bool = False
    auth_cookie_samesite: str = "lax"
    access_token_secret: str = "change-me-before-deploy"
    access_token_algorithm: str = "HS256"
    access_token_expire_minutes: int = 60 * 24
    password_hash_target_ms: int = 200
    email_provider: str = "dev"
    email_from_name: str = "面霸练习生"
    email_from_address: str = "no-reply@mianba.local"
    email_code_expire_seconds: int = 600
    email_code_rate_limit: int = 5
    email_code_rate_window_seconds: int = 600
    smtp_host: str = ""
    smtp_port: int = 465
    smtp_use_ssl: bool = True
    smtp_username: str = ""
    smtp_password: str = ""
    resend_api_key: str = ""
    brevo_api_key: str = ""
    zhipu_api_key: str = ""
    deepseek_api_key: str = ""
    aliyun_bailian_api_key: str = ""
    volcengine_ark_api_key: str = ""
    aliyun_directmail_access_key_id: str = ""
    aliyun_directmail_access_key_secret: str = ""
    tencent_ses_secret_id: str = ""
    tencent_ses_secret_key: str = ""
    interview_material_max_upload_bytes: int = 5 * 1024 * 1024
    resume_ocr_provider: str = "none"

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
