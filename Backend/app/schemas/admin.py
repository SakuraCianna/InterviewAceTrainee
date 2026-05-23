from pydantic import BaseModel, Field


class AdminCreditAdjustmentRequest(BaseModel):
    current_balance: int = Field(ge=0)
    change_amount: int
    reason: str = Field(min_length=2, max_length=80)
    operator_admin_id: str = Field(min_length=1)


class AdminCreditAdjustmentResponse(BaseModel):
    user_id: str
    change_amount: int
    balance_after: int
    reason: str
    operator_admin_id: str

