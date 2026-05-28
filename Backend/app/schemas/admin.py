from typing import Literal

from pydantic import BaseModel, Field

from app.schemas.interviews import InterviewHistoryItem, InterviewReportResponse


class AdminCreditAdjustmentRequest(BaseModel):
    current_balance: int | None = Field(default=None, ge=0)
    change_amount: int
    reason: str = Field(min_length=2, max_length=80)
    note: str | None = Field(default=None, max_length=240)


class AdminCreditAdjustmentResponse(BaseModel):
    user_id: str
    change_amount: int
    balance_after: int
    reason: str
    operator_admin_id: str


class AdminAuditLogResponse(BaseModel):
    id: str
    admin_email: str
    action: str
    target_type: str
    target_id: str
    before_snapshot: dict | None = None
    after_snapshot: dict | None = None
    ip_address: str | None = None
    user_agent: str | None = None
    created_at: str


class AdminCreditLedgerResponse(BaseModel):
    id: str
    user_email: str
    change_amount: int
    balance_after: int
    reason: str
    related_session_id: str | None = None
    operator_admin_email: str | None = None
    note: str | None = None
    created_at: str


class AdminAICallLogResponse(BaseModel):
    id: str
    session_id: str | None = None
    provider_type: str
    provider_name: str
    model_name: str
    purpose: str
    success: bool
    latency_ms: int | None = None
    provider_request_id: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    audio_duration_ms: int | None = None
    characters: int | None = None
    estimated_cost_cents: int | None = None
    error_message: str | None = None
    usage_json: dict | None = None
    created_at: str


class AdminContentSafetyLogResponse(BaseModel):
    id: str
    user_email: str | None = None
    session_id: str | None = None
    source: str
    action: str
    risk_level: str
    categories: list[str]
    matched_terms: list[str]
    content_excerpt: str | None = None
    message_code: str | None = None
    created_at: str


class AdminStatsPoint(BaseModel):
    label: str
    value: int | float


class AdminDashboardOverview(BaseModel):
    total_users: int
    active_users: int
    disabled_users: int
    admin_users: int
    total_credit_balance: int
    total_credit_granted: int
    total_sessions: int
    completed_sessions: int
    active_sessions: int
    today_sessions: int
    total_reports: int
    average_report_score: float | None = None
    ai_success_rate: float | None = None
    failed_login_count: int
    open_refund_cases: int


class AdminTopUserUsage(BaseModel):
    email: str
    total_interviews: int
    completed_interviews: int
    credit_balance: int
    last_interview_at: str | None = None


class AdminDashboardStatsResponse(BaseModel):
    database_ready: bool
    generated_at: str
    overview: AdminDashboardOverview
    user_growth: list[AdminStatsPoint]
    daily_interviews: list[AdminStatsPoint]
    daily_reports: list[AdminStatsPoint]
    interview_type_distribution: list[AdminStatsPoint]
    session_status_distribution: list[AdminStatsPoint]
    ai_call_success_distribution: list[AdminStatsPoint]
    login_outcome_distribution: list[AdminStatsPoint]
    refund_status_distribution: list[AdminStatsPoint]
    top_users: list[AdminTopUserUsage]


class AdminAuthLoginLogResponse(BaseModel):
    id: str
    email: str
    auth_method: str
    role: str
    success: bool
    failure_reason: str | None = None
    ip_address: str | None = None
    user_agent: str | None = None
    created_at: str


class CustomerServiceNoteCreateRequest(BaseModel):
    category: str = Field(default="general", min_length=2, max_length=80)
    content: str = Field(min_length=2, max_length=2000)
    related_session_id: str | None = Field(default=None, max_length=120)


class CustomerServiceNoteResponse(BaseModel):
    id: str
    user_email: str
    admin_email: str
    category: str
    content: str
    related_session_id: str | None = None
    created_at: str


class RefundCaseCreateRequest(BaseModel):
    reason: str = Field(min_length=2, max_length=120)
    description: str = Field(min_length=2, max_length=3000)
    amount_cents: int | None = Field(default=None, ge=0)
    currency: str = Field(default="CNY", min_length=3, max_length=16)
    credit_adjustment: int | None = None
    related_session_id: str | None = Field(default=None, max_length=120)


class RefundCaseUpdateRequest(BaseModel):
    status: str | None = Field(default=None, min_length=2, max_length=32)
    resolution: str | None = Field(default=None, max_length=3000)
    amount_cents: int | None = Field(default=None, ge=0)
    credit_adjustment: int | None = None


class RefundCaseResponse(BaseModel):
    id: str
    user_email: str
    status: str
    reason: str
    description: str
    amount_cents: int | None = None
    currency: str
    credit_adjustment: int | None = None
    related_session_id: str | None = None
    resolution: str | None = None
    created_by_admin_email: str
    updated_by_admin_email: str | None = None
    created_at: str
    updated_at: str


class AdminUserSearchItem(BaseModel):
    email: str
    role: str
    is_active: bool = True
    credit_balance: int
    total_interviews: int
    completed_interviews: int
    last_interview_at: str | None = None


class AdminUserDetailResponse(BaseModel):
    email: str
    role: str
    is_active: bool = True
    credit_balance: int
    total_interviews: int
    completed_interviews: int
    last_interview_at: str | None = None
    interviews: list[InterviewHistoryItem]


class AdminUserInterviewReportResponse(InterviewReportResponse):
    user_email: str


class AdminUserStatusUpdateRequest(BaseModel):
    is_active: bool
    reason: str = Field(min_length=2, max_length=120)


class AdminUserStatusResponse(BaseModel):
    email: str
    is_active: bool


class AdminUserRoleUpdateRequest(BaseModel):
    role: Literal["user", "admin"]
    reason: str = Field(min_length=2, max_length=120)


class AdminUserRoleResponse(BaseModel):
    email: str
    role: str


class SystemConfigResponse(BaseModel):
    key: str
    value: bool | int | float | str | dict | list | None
    description: str
    updated_at: str | None = None


class SystemConfigUpdateRequest(BaseModel):
    value: bool | int | float | str | dict | list | None
    description: str | None = Field(default=None, max_length=500)
