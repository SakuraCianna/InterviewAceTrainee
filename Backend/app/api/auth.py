from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.api.dependencies import TokenClaims, get_current_user_claims
from app.core.config import get_settings
from app.core.security import ACCESS_TOKEN_COOKIE_NAME, create_access_token, hash_password, is_email_in_allowlist, verify_password
from app.schemas.auth import (
    AdminLoginRequest,
    CurrentUserResponse,
    EmailCodeLoginRequest,
    EmailCodeRequest,
    EmailCodeRequestResponse,
    PasswordLoginRequest,
    PasswordLoginResponse,
    PasswordRegisterRequest,
)
from app.services.email_codes import (
    EmailCodeRateLimitError,
    EmailCodeStore,
    InvalidEmailCodeError,
    RedisEmailCodeStore,
    RedisEmailRateLimiter,
)
from app.services.mailers import EmailDeliveryError, send_verification_code_email
from app.services.redis_runtime import get_redis_client
from app.services.user_credentials import (
    UserAlreadyExistsError,
    UserCredentialStore,
    UserNotFoundError,
    get_user_credential_store,
)

router = APIRouter(prefix="/auth", tags=["auth"])

_demo_codes = EmailCodeStore()


def issue_login_response(response: Response, subject: str, role: str, settings) -> PasswordLoginResponse:
    access_token = create_access_token(subject, role)
    response.set_cookie(
        key=ACCESS_TOKEN_COOKIE_NAME,
        value=access_token,
        max_age=settings.access_token_expire_minutes * 60,
        httponly=True,
        secure=settings.auth_cookie_secure,
        samesite=settings.auth_cookie_samesite,
        path="/",
    )
    return PasswordLoginResponse(access_token=access_token, token_type="bearer")


def get_email_code_store(settings=Depends(get_settings)) -> EmailCodeStore | RedisEmailCodeStore:
    redis_client = get_redis_client()
    if redis_client is None:
        return _demo_codes
    return RedisEmailCodeStore(redis_client)


def check_email_code_rate_limit(email: str, settings=Depends(get_settings)) -> None:
    redis_client = get_redis_client()
    if redis_client is None:
        return

    limiter = RedisEmailRateLimiter(
        redis_client,
        limit=settings.email_code_rate_limit,
        window_seconds=settings.email_code_rate_window_seconds,
    )
    limiter.check(email)


@router.post("/password/register", response_model=PasswordLoginResponse, status_code=status.HTTP_201_CREATED)
def register_with_password(
    payload: PasswordRegisterRequest,
    response: Response,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> PasswordLoginResponse:
    try:
        code_store.consume_code(str(payload.email), payload.code)
    except InvalidEmailCodeError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    try:
        user_store.create_user(str(payload.email), hash_password(payload.password))
    except UserAlreadyExistsError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="email_already_registered") from exc

    return issue_login_response(response, str(payload.email), "user", settings)


@router.post("/password/login", response_model=PasswordLoginResponse)
def login_with_password(
    payload: PasswordLoginRequest,
    response: Response,
    settings=Depends(get_settings),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> PasswordLoginResponse:
    try:
        password_hash = user_store.require_password_hash(str(payload.email))
    except UserNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials") from exc

    if not verify_password(payload.password, password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials")

    return issue_login_response(response, str(payload.email), "user", settings)


@router.post("/email-code/request", response_model=EmailCodeRequestResponse, status_code=status.HTTP_202_ACCEPTED)
def request_email_code(
    payload: EmailCodeRequest,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
) -> EmailCodeRequestResponse:
    try:
        check_email_code_rate_limit(str(payload.email), settings=settings)
    except EmailCodeRateLimitError as exc:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="email_code_rate_limited") from exc

    record = code_store.issue_code(str(payload.email), expires_in_seconds=settings.email_code_expire_seconds)
    try:
        send_verification_code_email(
            settings=settings,
            to_email=str(payload.email),
            code=record.code,
            expires_in_seconds=settings.email_code_expire_seconds,
        )
    except EmailDeliveryError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc

    dev_code = record.code if settings.email_provider == "dev" else ""
    return EmailCodeRequestResponse(email=payload.email, expires_in_seconds=settings.email_code_expire_seconds, dev_code=dev_code)


@router.post("/email-code/login", response_model=PasswordLoginResponse)
def login_with_email_code(
    payload: EmailCodeLoginRequest,
    response: Response,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> PasswordLoginResponse:
    try:
        code_store.consume_code(str(payload.email), payload.code)
    except InvalidEmailCodeError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    user_store.create_placeholder_user(str(payload.email))
    return issue_login_response(response, str(payload.email), "user", settings)


@router.post("/admin/login", response_model=PasswordLoginResponse)
def login_admin(
    payload: AdminLoginRequest,
    response: Response,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
) -> PasswordLoginResponse:
    try:
        password_hash = user_store.require_password_hash(str(payload.email))
    except UserNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials") from exc

    if not verify_password(payload.password, password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials")

    if not is_email_in_allowlist(str(payload.email), settings.admin_email_allowlist):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_email_not_allowed")

    try:
        code_store.consume_code(str(payload.email), payload.code)
    except InvalidEmailCodeError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    return issue_login_response(response, str(payload.email), "admin", settings)


@router.get("/me", response_model=CurrentUserResponse)
def read_current_user(claims: TokenClaims = Depends(get_current_user_claims)) -> CurrentUserResponse:
    return CurrentUserResponse(email=claims["sub"], role=claims["role"])
