from typing import TypedDict

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.security import ACCESS_TOKEN_COOKIE_NAME, CSRF_TOKEN_COOKIE_NAME, CSRF_TOKEN_HEADER_NAME, decode_access_token


class TokenClaims(TypedDict):
    sub: str
    role: str


bearer_scheme = HTTPBearer(auto_error=False)


def get_current_user_claims(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> TokenClaims:
    token = credentials.credentials if credentials is not None else request.cookies.get(ACCESS_TOKEN_COOKIE_NAME)
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing_access_token")
    if credentials is None:
        require_csrf_for_cookie_auth(request)

    try:
        return decode_access_token(token)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_access_token") from exc


def require_admin_user(claims: TokenClaims = Depends(get_current_user_claims)) -> TokenClaims:
    if claims["role"] != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_required")
    return claims


def require_csrf_for_cookie_auth(request: Request) -> None:
    if request.method.upper() in {"GET", "HEAD", "OPTIONS", "TRACE"}:
        return
    csrf_cookie = request.cookies.get(CSRF_TOKEN_COOKIE_NAME)
    csrf_header = request.headers.get(CSRF_TOKEN_HEADER_NAME)
    if not csrf_cookie or not csrf_header or csrf_cookie != csrf_header:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="csrf_token_required")
