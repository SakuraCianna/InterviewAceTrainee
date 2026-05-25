from datetime import datetime, timedelta, timezone
from secrets import token_urlsafe

from jose import JWTError, jwt
from pwdlib import PasswordHash

from app.core.config import get_settings

password_hash = PasswordHash.recommended()
ACCESS_TOKEN_COOKIE_NAME = "mianba_access_token"
CSRF_TOKEN_COOKIE_NAME = "mianba_csrf_token"
CSRF_TOKEN_HEADER_NAME = "X-CSRF-Token"


def hash_password(password: str) -> str:
    return password_hash.hash(password)


def verify_password(password: str, password_digest: str) -> bool:
    return password_hash.verify(password, password_digest)


def create_csrf_token() -> str:
    return token_urlsafe(32)


def create_access_token(subject: str, role: str, session_id: str) -> str:
    settings = get_settings()
    issued_at = datetime.now(timezone.utc)
    expires_at = issued_at + timedelta(minutes=settings.access_token_expire_minutes)
    payload = {"sub": subject, "role": role, "sid": session_id, "iat": issued_at, "exp": expires_at}
    return jwt.encode(payload, settings.access_token_secret, algorithm=settings.access_token_algorithm)


def decode_access_token(token: str) -> dict[str, str]:
    settings = get_settings()
    try:
        payload = jwt.decode(token, settings.access_token_secret, algorithms=[settings.access_token_algorithm])
    except JWTError as exc:
        raise ValueError("invalid access token") from exc

    subject = payload.get("sub")
    role = payload.get("role")
    session_id = payload.get("sid")
    if not isinstance(subject, str) or not isinstance(role, str) or not isinstance(session_id, str):
        raise ValueError("invalid access token claims")
    return {"sub": subject, "role": role, "session_id": session_id}
