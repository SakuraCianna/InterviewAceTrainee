from pydantic import BaseModel, EmailStr, Field


class PasswordRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    code: str = Field(min_length=6, max_length=6)


class PasswordLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)


class PasswordLoginResponse(BaseModel):
    access_token: str
    token_type: str


class CurrentUserResponse(BaseModel):
    email: EmailStr
    role: str
    credit_balance: int


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
