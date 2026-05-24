from base64 import urlsafe_b64encode
from hashlib import sha256

from cryptography.fernet import Fernet, InvalidToken

from app.core.config import Settings


def preview_secret(secret: str | None) -> str | None:
    if not secret:
        return None
    if len(secret) <= 8:
        return "*" * len(secret)
    return f"{secret[:4]}...{secret[-4:]}"


def encrypt_secret(secret: str | None, settings: Settings) -> str | None:
    if not secret:
        return None
    return _fernet(settings).encrypt(secret.encode("utf-8")).decode("utf-8")


def decrypt_secret(encrypted_secret: str | None, settings: Settings) -> str:
    if not encrypted_secret:
        return ""
    try:
        return _fernet(settings).decrypt(encrypted_secret.encode("utf-8")).decode("utf-8")
    except InvalidToken:
        return ""


def _fernet(settings: Settings) -> Fernet:
    seed = settings.provider_secret_encryption_key or settings.access_token_secret
    key = urlsafe_b64encode(sha256(seed.encode("utf-8")).digest())
    return Fernet(key)
