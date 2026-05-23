from enum import StrEnum

from pydantic import BaseModel, Field


class InterviewType(StrEnum):
    JOB = "job"
    POSTGRADUATE = "postgraduate"
    CIVIL_SERVICE = "civil_service"
    IELTS = "ielts"


class InterviewStartRequest(BaseModel):
    session_id: str = Field(min_length=1)
    current_credit_balance: int = Field(ge=0)
    is_admin: bool = False
    interview_type: InterviewType | None = None


class InterviewStartResponse(BaseModel):
    session_id: str
    interview_type: InterviewType
    credit_change: int
    balance_after: int
    ledger_reason: str


class InterviewProduct(BaseModel):
    id: InterviewType
    name: str
    tagline: str
    description: str
    rounds: list[str]
    credit_cost: int
    pricing_unit: str
    report_focus: list[str]
