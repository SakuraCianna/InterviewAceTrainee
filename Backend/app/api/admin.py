from fastapi import APIRouter, HTTPException, status

from app.schemas.admin import AdminCreditAdjustmentRequest, AdminCreditAdjustmentResponse

router = APIRouter(prefix="/admin", tags=["admin"])


@router.post("/users/{user_id}/credits", response_model=AdminCreditAdjustmentResponse)
def adjust_user_credits(user_id: str, payload: AdminCreditAdjustmentRequest) -> AdminCreditAdjustmentResponse:
    if not payload.operator_admin_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin_required")

    balance_after = payload.current_balance + payload.change_amount
    if balance_after < 0:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="credit_balance_cannot_be_negative")

    return AdminCreditAdjustmentResponse(
        user_id=user_id,
        change_amount=payload.change_amount,
        balance_after=balance_after,
        reason=payload.reason,
        operator_admin_id=payload.operator_admin_id,
    )

