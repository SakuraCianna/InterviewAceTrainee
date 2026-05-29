from pydantic import BaseModel, EmailStr, Field


class PasswordRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    code: str = Field(min_length=6, max_length=6)


class PasswordLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)


class PasswordResetRequest(BaseModel):
    email: EmailStr
    code: str = Field(min_length=6, max_length=6)
    new_password: str = Field(min_length=8, max_length=128)


class PasswordChangeRequest(BaseModel):
    code: str = Field(min_length=6, max_length=6)
    new_password: str = Field(min_length=8, max_length=128)


class PasswordMutationResponse(BaseModel):
    email: EmailStr
    message: str


class PasswordLoginResponse(BaseModel):
    access_token: str
    token_type: str


class CurrentUserResponse(BaseModel):
    email: EmailStr
    role: str
    credit_balance: int
    trial_voucher_count: int = 0


class EmailCodeRequest(BaseModel):
    email: EmailStr


class EmailCodeRequestResponse(BaseModel):
    email: EmailStr
    expires_in_seconds: int
    dev_code: str


class EmailCodeLoginRequest(BaseModel):
    email: EmailStr
    code: str = Field(min_length=6, max_length=6)


class AdminLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    code: str = Field(min_length=6, max_length=6)
