from fastapi import APIRouter, Depends, HTTPException, Request, Response, status

from app.api.dependencies import TokenClaims, get_current_user_claims, require_csrf_for_cookie_auth
from app.core.config import get_settings
from app.core.security import (
    ACCESS_TOKEN_COOKIE_NAME,
    CSRF_TOKEN_COOKIE_NAME,
    create_access_token,
    create_csrf_token,
    decode_access_token,
    hash_password,
    verify_password,
)
from app.schemas.auth import (
    AdminLoginRequest,
    CurrentUserResponse,
    EmailCodeLoginRequest,
    EmailCodeRequest,
    EmailCodeRequestResponse,
    PasswordChangeRequest,
    PasswordLoginRequest,
    PasswordLoginResponse,
    PasswordMutationResponse,
    PasswordRegisterRequest,
    PasswordResetRequest,
)
from app.services.auth_login_logs import AuthLoginLogStore, get_auth_login_log_store
from app.services.auth_sessions import AuthSessionStore, get_auth_session_store
from app.services.credit_balances import CreditBalanceStore, get_credit_balance_store
from app.services.email_codes import (
    AuthAttemptRateLimitError,
    EmailCodeRateLimitError,
    EmailCodeStore,
    InMemoryAuthAttemptLimiter,
    InMemoryEmailRateLimiter,
    InvalidEmailCodeError,
    RedisAuthAttemptLimiter,
    RedisEmailCodeStore,
    RedisEmailRateLimiter,
)
from app.services.mailers import EmailDeliveryError, send_verification_code_email
from app.services.redis_runtime import get_redis_client
from app.services.system_configs import DatabaseSystemConfigStore, InMemorySystemConfigStore, get_system_config_store
from app.services.interview_vouchers import InterviewVoucherStore, get_interview_voucher_store
from app.services.user_credentials import (
    UserAlreadyExistsError,
    UserCredentialStore,
    UserDisabledError,
    UserNotFoundError,
    get_user_credential_store,
)

router = APIRouter(prefix="/auth", tags=["auth"])

_demo_codes = EmailCodeStore()
_auth_attempt_limiter = InMemoryAuthAttemptLimiter()
_email_code_limiter = InMemoryEmailRateLimiter()


def issue_login_response(
    response: Response,
    subject: str,
    role: str,
    settings,
    auth_session_store: AuthSessionStore,
) -> PasswordLoginResponse:
    session_id = auth_session_store.create_session(subject, settings.access_token_expire_minutes * 60)
    access_token = create_access_token(subject, role, session_id)
    csrf_token = create_csrf_token()
    response.set_cookie(
        key=ACCESS_TOKEN_COOKIE_NAME,
        value=access_token,
        max_age=settings.access_token_expire_minutes * 60,
        httponly=True,
        secure=settings.auth_cookie_secure,
        samesite=settings.auth_cookie_samesite,
        path="/",
    )
    response.set_cookie(
        key=CSRF_TOKEN_COOKIE_NAME,
        value=csrf_token,
        max_age=settings.access_token_expire_minutes * 60,
        httponly=False,
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


def check_email_code_rate_limit(email: str, request: Request | None = None, settings=Depends(get_settings)) -> None:
    rate_limit_keys = [("email", email, settings.email_code_rate_limit, settings.email_code_rate_window_seconds)]
    if request is not None:
        ip_address = client_ip(request)
        if ip_address:
            rate_limit_keys.append(("ip", ip_address, settings.email_code_ip_rate_limit, settings.email_code_ip_rate_window_seconds))
    domain = email.rsplit("@", 1)[-1].strip().lower() if "@" in email else ""
    if domain:
        rate_limit_keys.append(("domain", domain, settings.email_code_domain_rate_limit, settings.email_code_domain_rate_window_seconds))

    redis_client = get_redis_client()
    if redis_client is None:
        for scope, key, limit, window_seconds in rate_limit_keys:
            _email_code_limiter.check(f"{scope}:{key}", limit=limit, window_seconds=window_seconds)
        return

    for scope, key, limit, window_seconds in rate_limit_keys:
        limiter = RedisEmailRateLimiter(redis_client, limit=limit, window_seconds=window_seconds)
        limiter.check(f"{scope}:{key}")


def auth_attempt_key(flow: str, email: str) -> str:
    return f"{flow}:{email.strip().lower()}"


def get_auth_attempt_limiter() -> InMemoryAuthAttemptLimiter | RedisAuthAttemptLimiter:
    redis_client = get_redis_client()
    if redis_client is None:
        return _auth_attempt_limiter
    return RedisAuthAttemptLimiter(redis_client)


def check_auth_attempts(key: str, settings) -> None:
    try:
        get_auth_attempt_limiter().check(
            key,
            limit=settings.auth_failure_limit,
            window_seconds=settings.auth_failure_window_seconds,
        )
    except AuthAttemptRateLimitError as exc:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="auth_attempt_rate_limited") from exc


def record_auth_failure(key: str, settings) -> None:
    get_auth_attempt_limiter().record_failure(
        key,
        limit=settings.auth_failure_limit,
        window_seconds=settings.auth_failure_window_seconds,
    )


def reset_auth_failures(key: str) -> None:
    get_auth_attempt_limiter().reset(key)


def client_ip(request: Request) -> str | None:
    forwarded_for = request.headers.get("x-forwarded-for")
    if forwarded_for:
        return forwarded_for.split(",")[0].strip()
    return request.client.host if request.client else None


def normalize_auth_email(email: object) -> str:
    return str(email).strip().lower()


def get_authenticated_role(user_store: UserCredentialStore, email: str) -> str:
    user_record = user_store.get_user_record(email)
    return user_record.role if user_record is not None else "user"


def reject_admin_on_user_login(
    user_store: UserCredentialStore,
    email: str,
    login_log_store: AuthLoginLogStore,
    request: Request,
    auth_method: str,
) -> None:
    user_record = user_store.get_user_record(email)
    if user_record is None or user_record.role != "admin":
        return
    record_login_event(
        login_log_store,
        request,
        email=email,
        auth_method=auth_method,
        role="admin",
        success=False,
        failure_reason="admin_login_required",
    )
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_login_required")


def issue_registration_vouchers(
    email: str,
    system_config_store: DatabaseSystemConfigStore | InMemorySystemConfigStore,
    voucher_store: InterviewVoucherStore,
) -> None:
    voucher_count = max(0, system_config_store.get_int("new_user_trial_vouchers"))
    if voucher_count <= 0:
        return
    voucher_store.issue_many(
        [email],
        voucher_type="new_user_trial",
        issue_reason="registration_bonus",
        quantity=voucher_count,
        note="new_user_trial",
    )


def record_login_event(
    log_store: AuthLoginLogStore,
    request: Request,
    *,
    email: str,
    auth_method: str,
    role: str,
    success: bool,
    failure_reason: str | None = None,
) -> None:
    log_store.record(
        email=email,
        auth_method=auth_method,
        role=role,
        success=success,
        failure_reason=failure_reason,
        ip_address=client_ip(request),
        user_agent=request.headers.get("user-agent"),
    )


@router.post("/password/register", response_model=PasswordLoginResponse, status_code=status.HTTP_201_CREATED)
def register_with_password(
    request: Request,
    payload: PasswordRegisterRequest,
    response: Response,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    system_config_store: DatabaseSystemConfigStore | InMemorySystemConfigStore = Depends(get_system_config_store),
    voucher_store: InterviewVoucherStore = Depends(get_interview_voucher_store),
    login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> PasswordLoginResponse:
    email = normalize_auth_email(payload.email)
    attempt_key = auth_attempt_key("password_register", email)
    check_auth_attempts(attempt_key, settings)
    if not system_config_store.get_bool("registration_open"):
        record_login_event(login_log_store, request, email=email, auth_method="password_register", role="user", success=False, failure_reason="registration_closed")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="registration_closed")
    try:
        code_store.consume_code(email, payload.code)
    except InvalidEmailCodeError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password_register", role="user", success=False, failure_reason="invalid_email_code")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    reject_admin_on_user_login(user_store, email, login_log_store, request, "password_register")
    try:
        is_new_user = not user_store.user_exists(email)
        user_store.create_user(
            email,
            hash_password(payload.password),
            initial_credit_balance=system_config_store.get_int("new_user_default_credits"),
        )
        if is_new_user:
            issue_registration_vouchers(email, system_config_store, voucher_store)
    except UserAlreadyExistsError as exc:
        record_login_event(login_log_store, request, email=email, auth_method="password_register", role="user", success=False, failure_reason="email_already_registered")
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="email_already_registered") from exc
    except UserDisabledError as exc:
        record_login_event(login_log_store, request, email=email, auth_method="password_register", role="user", success=False, failure_reason="user_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_disabled") from exc

    reset_auth_failures(attempt_key)
    role = get_authenticated_role(user_store, email)
    record_login_event(login_log_store, request, email=email, auth_method="password_register", role=role, success=True)
    return issue_login_response(response, email, role, settings, auth_session_store)


@router.post("/password/login", response_model=PasswordLoginResponse)
def login_with_password(
    request: Request,
    payload: PasswordLoginRequest,
    response: Response,
    settings=Depends(get_settings),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    system_config_store: DatabaseSystemConfigStore | InMemorySystemConfigStore = Depends(get_system_config_store),
    login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> PasswordLoginResponse:
    email = normalize_auth_email(payload.email)
    attempt_key = auth_attempt_key("password_login", email)
    check_auth_attempts(attempt_key, settings)
    if not system_config_store.get_bool("password_login_enabled"):
        record_login_event(login_log_store, request, email=email, auth_method="password", role="user", success=False, failure_reason="password_login_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="password_login_disabled")
    try:
        password_hash = user_store.require_password_hash(email)
    except UserNotFoundError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password", role="user", success=False, failure_reason="invalid_credentials")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials") from exc
    except UserDisabledError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password", role="user", success=False, failure_reason="user_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_disabled") from exc

    if not verify_password(payload.password, password_hash):
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password", role="user", success=False, failure_reason="invalid_credentials")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials")
    reject_admin_on_user_login(user_store, email, login_log_store, request, "password")

    reset_auth_failures(attempt_key)
    role = get_authenticated_role(user_store, email)
    record_login_event(login_log_store, request, email=email, auth_method="password", role=role, success=True)
    return issue_login_response(response, email, role, settings, auth_session_store)


@router.post("/password/reset", response_model=PasswordMutationResponse)
def reset_password_with_email_code(
    request: Request,
    payload: PasswordResetRequest,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
) -> PasswordMutationResponse:
    email = normalize_auth_email(payload.email)
    attempt_key = auth_attempt_key("password_reset", email)
    check_auth_attempts(attempt_key, settings)
    user_record = user_store.get_user_record(email)
    if user_record is None:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password_reset", role="user", success=False, failure_reason="user_not_found")
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found")
    if not user_record.is_active:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password_reset", role=user_record.role, success=False, failure_reason="user_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_disabled")
    try:
        code_store.consume_code(email, payload.code)
    except InvalidEmailCodeError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password_reset", role=user_record.role, success=False, failure_reason="invalid_email_code")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    user_store.set_password(email, hash_password(payload.new_password))

    reset_auth_failures(attempt_key)
    record_login_event(login_log_store, request, email=email, auth_method="password_reset", role=user_record.role, success=True)
    return PasswordMutationResponse(email=email, message="password_reset_success")


@router.post("/password/change", response_model=PasswordMutationResponse)
def change_current_user_password(
    request: Request,
    payload: PasswordChangeRequest,
    claims: TokenClaims = Depends(get_current_user_claims),
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
) -> PasswordMutationResponse:
    email = normalize_auth_email(claims["sub"])
    attempt_key = auth_attempt_key("password_change", email)
    check_auth_attempts(attempt_key, settings)
    try:
        code_store.consume_code(email, payload.code)
    except InvalidEmailCodeError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password_change", role=claims["role"], success=False, failure_reason="invalid_email_code")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    try:
        user_store.set_password(email, hash_password(payload.new_password))
    except UserNotFoundError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password_change", role=claims["role"], success=False, failure_reason="user_not_found")
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user_not_found") from exc
    except UserDisabledError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="password_change", role=claims["role"], success=False, failure_reason="user_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_disabled") from exc

    reset_auth_failures(attempt_key)
    record_login_event(login_log_store, request, email=email, auth_method="password_change", role=claims["role"], success=True)
    return PasswordMutationResponse(email=email, message="password_change_success")


@router.post("/email-code/request", response_model=EmailCodeRequestResponse, status_code=status.HTTP_202_ACCEPTED)
def request_email_code(
    request: Request,
    payload: EmailCodeRequest,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
) -> EmailCodeRequestResponse:
    email = normalize_auth_email(payload.email)
    try:
        check_email_code_rate_limit(email, request=request, settings=settings)
    except EmailCodeRateLimitError as exc:
        retry_after = max(1, exc.retry_after_seconds or settings.email_code_rate_window_seconds)
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="email_code_rate_limited",
            headers={"Retry-After": str(retry_after)},
        ) from exc

    record = code_store.issue_code(email, expires_in_seconds=settings.email_code_expire_seconds)
    try:
        send_verification_code_email(
            settings=settings,
            to_email=email,
            code=record.code,
            expires_in_seconds=settings.email_code_expire_seconds,
        )
    except EmailDeliveryError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc

    dev_code = record.code if settings.email_provider == "dev" else ""
    return EmailCodeRequestResponse(email=email, expires_in_seconds=settings.email_code_expire_seconds, dev_code=dev_code)


@router.post("/email-code/login", response_model=PasswordLoginResponse)
def login_with_email_code(
    request: Request,
    payload: EmailCodeLoginRequest,
    response: Response,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    system_config_store: DatabaseSystemConfigStore | InMemorySystemConfigStore = Depends(get_system_config_store),
    voucher_store: InterviewVoucherStore = Depends(get_interview_voucher_store),
    login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> PasswordLoginResponse:
    email = normalize_auth_email(payload.email)
    attempt_key = auth_attempt_key("email_code_login", email)
    check_auth_attempts(attempt_key, settings)
    if not system_config_store.get_bool("email_code_login_enabled"):
        record_login_event(login_log_store, request, email=email, auth_method="email_code", role="user", success=False, failure_reason="email_code_login_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="email_code_login_disabled")
    if not user_store.user_exists(email) and not system_config_store.get_bool("registration_open"):
        record_login_event(login_log_store, request, email=email, auth_method="email_code", role="user", success=False, failure_reason="registration_closed")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="registration_closed")
    try:
        code_store.consume_code(email, payload.code)
    except InvalidEmailCodeError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="email_code", role="user", success=False, failure_reason="invalid_email_code")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    reject_admin_on_user_login(user_store, email, login_log_store, request, "email_code")
    try:
        is_new_user = not user_store.user_exists(email)
        user_store.create_placeholder_user(
            email,
            initial_credit_balance=system_config_store.get_int("new_user_default_credits"),
        )
        if is_new_user:
            issue_registration_vouchers(email, system_config_store, voucher_store)
    except UserDisabledError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="email_code", role="user", success=False, failure_reason="user_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_disabled") from exc
    reset_auth_failures(attempt_key)
    role = get_authenticated_role(user_store, email)
    record_login_event(login_log_store, request, email=email, auth_method="email_code", role=role, success=True)
    return issue_login_response(response, email, role, settings, auth_session_store)


@router.post("/admin/login", response_model=PasswordLoginResponse)
def login_admin(
    request: Request,
    payload: AdminLoginRequest,
    response: Response,
    settings=Depends(get_settings),
    code_store: EmailCodeStore | RedisEmailCodeStore = Depends(get_email_code_store),
    user_store: UserCredentialStore = Depends(get_user_credential_store),
    login_log_store: AuthLoginLogStore = Depends(get_auth_login_log_store),
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> PasswordLoginResponse:
    email = normalize_auth_email(payload.email)
    attempt_key = auth_attempt_key("admin_login", email)
    check_auth_attempts(attempt_key, settings)
    try:
        password_hash = user_store.require_password_hash(email)
    except UserNotFoundError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="admin_password_email_code", role="admin", success=False, failure_reason="invalid_credentials")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials") from exc
    except UserDisabledError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="admin_password_email_code", role="admin", success=False, failure_reason="user_disabled")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_disabled") from exc

    if not verify_password(payload.password, password_hash):
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="admin_password_email_code", role="admin", success=False, failure_reason="invalid_credentials")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials")

    user_record = user_store.get_user_record(email)
    if user_record is None or user_record.role != "admin":
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="admin_password_email_code", role="admin", success=False, failure_reason="admin_role_required")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_role_required")

    try:
        code_store.consume_code(email, payload.code)
    except InvalidEmailCodeError as exc:
        record_auth_failure(attempt_key, settings)
        record_login_event(login_log_store, request, email=email, auth_method="admin_password_email_code", role="admin", success=False, failure_reason="invalid_email_code")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    reset_auth_failures(attempt_key)
    record_login_event(login_log_store, request, email=email, auth_method="admin_password_email_code", role="admin", success=True)
    return issue_login_response(response, email, "admin", settings, auth_session_store)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(
    request: Request,
    response: Response,
    settings=Depends(get_settings),
    auth_session_store: AuthSessionStore = Depends(get_auth_session_store),
) -> None:
    if request.cookies.get(ACCESS_TOKEN_COOKIE_NAME):
        require_csrf_for_cookie_auth(request)
        try:
            claims = decode_access_token(request.cookies[ACCESS_TOKEN_COOKIE_NAME])
            auth_session_store.clear_session(claims["sub"], claims["session_id"])
        except ValueError:
            pass
    response.delete_cookie(
        key=ACCESS_TOKEN_COOKIE_NAME,
        path="/",
        secure=settings.auth_cookie_secure,
        samesite=settings.auth_cookie_samesite,
    )
    response.delete_cookie(
        key=CSRF_TOKEN_COOKIE_NAME,
        path="/",
        secure=settings.auth_cookie_secure,
        samesite=settings.auth_cookie_samesite,
    )


@router.get("/me", response_model=CurrentUserResponse)
def read_current_user(
    claims: TokenClaims = Depends(get_current_user_claims),
    credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    voucher_store: InterviewVoucherStore = Depends(get_interview_voucher_store),
) -> CurrentUserResponse:
    return CurrentUserResponse(
        email=claims["sub"],
        role=claims["role"],
        credit_balance=credit_store.get_balance(claims["sub"]),
        trial_voucher_count=voucher_store.available_count(claims["sub"]),
    )
