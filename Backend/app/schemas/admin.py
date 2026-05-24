from pydantic import BaseModel, Field


class AdminCreditAdjustmentRequest(BaseModel):
    current_balance: int | None = Field(default=None, ge=0)
    change_amount: int
    reason: str = Field(min_length=2, max_length=80)


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
    error_message: str | None = None
    usage_json: dict | None = None
    created_at: str
