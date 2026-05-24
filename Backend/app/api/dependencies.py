from typing import TypedDict

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.security import ACCESS_TOKEN_COOKIE_NAME, decode_access_token


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

    try:
        return decode_access_token(token)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_access_token") from exc


def require_admin_user(claims: TokenClaims = Depends(get_current_user_claims)) -> TokenClaims:
    if claims["role"] != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_required")
    return claims
