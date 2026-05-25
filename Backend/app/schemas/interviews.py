from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, Field


class InterviewType(StrEnum):
    JOB = "job"
    POSTGRADUATE = "postgraduate"
    CIVIL_SERVICE = "civil_service"
    IELTS = "ielts"


class InterviewStartRequest(BaseModel):
    session_id: str = Field(min_length=1)
    interview_type: InterviewType | None = None
    material_id: str | None = None


class InterviewQuestion(BaseModel):
    turn_index: int
    round_name: str
    text: str


class InterviewReportDimension(BaseModel):
    name: str
    score: int
    comment: str
    level: str | None = None
    evidence: list[str] = Field(default_factory=list)
    action: str | None = None


class InterviewReportTurn(BaseModel):
    round_name: str
    question: str
    answer: str
    score: int | None = None
    feedback: str | None = None
    evidence: list[str] = Field(default_factory=list)


class InterviewReportResponse(BaseModel):
    session_id: str
    interview_type: InterviewType
    total_score: int
    readiness_level: str = "待复盘"
    score_explanation: str = ""
    summary: str
    dimensions: list[InterviewReportDimension]
    strengths: list[str]
    improvements: list[str]
    next_plan: list[str]
    priority_actions: list[str] = Field(default_factory=list)
    evidence: list[str] = Field(default_factory=list)
    risk_flags: list[str] = Field(default_factory=list)
    recommended_drills: list[str] = Field(default_factory=list)
    turns: list[InterviewReportTurn]


class InterviewStartResponse(BaseModel):
    session_id: str
    interview_type: InterviewType
    credit_change: int
    balance_after: int
    ledger_reason: str
    status: str
    current_step_index: int
    total_steps: int
    current_question: InterviewQuestion | None = None
    report: InterviewReportResponse | None = None


class InterviewAnswerRequest(BaseModel):
    answer_text: str = Field(min_length=1, max_length=8000)


class InterviewAnswerResponse(BaseModel):
    session_id: str
    interview_type: InterviewType
    status: str
    current_step_index: int
    total_steps: int
    current_question: InterviewQuestion | None = None
    report: InterviewReportResponse | None = None


class InterviewHistoryItem(BaseModel):
    session_id: str
    interview_type: InterviewType
    status: str
    current_step_index: int
    total_steps: int
    report_total_score: int | None = None
    created_at: datetime


class InterviewProduct(BaseModel):
    id: InterviewType
    name: str
    tagline: str
    description: str
    rounds: list[str]
    credit_cost: int
    pricing_unit: str
    report_focus: list[str]


class InterviewMaterialResponse(BaseModel):
    id: str
    interview_type: InterviewType
    job_title: str | None = None
    job_requirements: str | None = None
    major: str | None = None
    research_direction: str | None = None
    resume_filename: str | None = None
    resume_content_type: str | None = None
    resume_text_preview: str | None = None
    extracted_text_chars: int = 0
    profile_summary: str
    keywords: list[str]
    created_at: datetime
