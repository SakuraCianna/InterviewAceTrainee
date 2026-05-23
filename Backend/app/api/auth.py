from fastapi import APIRouter, HTTPException, status

from app.core.security import hash_password, verify_password
from app.schemas.auth import (
    AdminLoginRequest,
    EmailCodeLoginRequest,
    EmailCodeRequest,
    EmailCodeRequestResponse,
    PasswordLoginRequest,
    PasswordLoginResponse,
    PasswordRegisterRequest,
)
from app.services.email_codes import EmailCodeStore, InvalidEmailCodeError

router = APIRouter(prefix="/auth", tags=["auth"])

_demo_users: dict[str, str] = {}
_demo_codes = EmailCodeStore()


@router.post("/password/register", response_model=PasswordLoginResponse, status_code=status.HTTP_201_CREATED)
def register_with_password(payload: PasswordRegisterRequest) -> PasswordLoginResponse:
    if payload.email in _demo_users:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="email_already_registered")

    _demo_users[payload.email] = hash_password(payload.password)
    return PasswordLoginResponse(access_token=f"demo-token:{payload.email}", token_type="bearer")


@router.post("/password/login", response_model=PasswordLoginResponse)
def login_with_password(payload: PasswordLoginRequest) -> PasswordLoginResponse:
    password_hash = _demo_users.get(payload.email)
    if password_hash is None or not verify_password(payload.password, password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials")

    return PasswordLoginResponse(access_token=f"demo-token:{payload.email}", token_type="bearer")


@router.post("/email-code/request", response_model=EmailCodeRequestResponse, status_code=status.HTTP_202_ACCEPTED)
def request_email_code(payload: EmailCodeRequest) -> EmailCodeRequestResponse:
    record = _demo_codes.issue_code(str(payload.email))
    return EmailCodeRequestResponse(email=payload.email, expires_in_seconds=600, dev_code=record.code)


@router.post("/email-code/login", response_model=PasswordLoginResponse)
def login_with_email_code(payload: EmailCodeLoginRequest) -> PasswordLoginResponse:
    try:
        _demo_codes.consume_code(str(payload.email), payload.code)
    except InvalidEmailCodeError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    return PasswordLoginResponse(access_token=f"demo-token:{payload.email}", token_type="bearer")


@router.post("/admin/login", response_model=PasswordLoginResponse)
def login_admin(payload: AdminLoginRequest) -> PasswordLoginResponse:
    password_hash = _demo_users.get(payload.email)
    if password_hash is None or not verify_password(payload.password, password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials")

    try:
        _demo_codes.consume_code(str(payload.email), payload.code)
    except InvalidEmailCodeError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_email_code") from exc

    return PasswordLoginResponse(access_token=f"admin-demo-token:{payload.email}", token_type="bearer")
