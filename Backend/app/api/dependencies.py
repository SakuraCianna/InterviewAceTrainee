from typing import TypedDict

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.security import ACCESS_TOKEN_COOKIE_NAME, CSRF_TOKEN_COOKIE_NAME, CSRF_TOKEN_HEADER_NAME, decode_access_token
from app.services.auth_sessions import AuthSessionStore, get_auth_session_store
from app.services.user_credentials import UserCredentialStore, get_user_credential_store


class TokenClaims(TypedDict):
    sub: str
    role: str
    session_id: str


bearer_scheme = HTTPBearer(auto_error=False)


def get_current_user_claims(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> TokenClaims:
    token = credentials.credentials if credentials is not None else request.cookies.get(ACCESS_TOKEN_COOKIE_NAME)
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing_access_token")
    if credentials is None:
        require_csrf_for_cookie_auth(request)

    try:
        claims = decode_access_token(token)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_access_token") from exc
    if not auth_session_store.is_current_session(claims["sub"], claims["session_id"]):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="session_replaced")
    if not user_store.is_active(claims["sub"]):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_disabled")
    return claims


def require_admin_user(
    claims: TokenClaims = Depends(get_current_user_claims),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> TokenClaims:
    if claims["role"] != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_required")
    user_record = user_store.get_user_record(claims["sub"])
    if user_record is None or user_record.role != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_required")
    return claims


def require_csrf_for_cookie_auth(request: Request) -> None:
    if request.method.upper() in {"GET", "HEAD", "OPTIONS", "TRACE"}:
        return
    csrf_cookie = request.cookies.get(CSRF_TOKEN_COOKIE_NAME)
    csrf_header = request.headers.get(CSRF_TOKEN_HEADER_NAME)
    if not csrf_cookie or not csrf_header or csrf_cookie != csrf_header:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="csrf_token_required")
