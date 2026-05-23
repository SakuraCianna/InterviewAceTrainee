from fastapi import APIRouter, HTTPException, status

from app.schemas.interviews import InterviewStartRequest, InterviewStartResponse, InterviewType
from app.services.credits import CreditLedger, InsufficientCreditsError

router = APIRouter(prefix="/interviews", tags=["interviews"])


@router.post("", response_model=InterviewStartResponse, status_code=status.HTTP_201_CREATED)
def start_interview(payload: InterviewStartRequest) -> InterviewStartResponse:
    ledger = CreditLedger(initial_balance=payload.current_credit_balance, is_admin=payload.is_admin)

    try:
        entry = ledger.consume_for_interview(payload.session_id)
    except InsufficientCreditsError as exc:
        raise HTTPException(status_code=status.HTTP_402_PAYMENT_REQUIRED, detail="insufficient_credits") from exc

    return InterviewStartResponse(
        session_id=payload.session_id,
        interview_type=payload.interview_type or InterviewType.JOB,
        credit_change=entry.change_amount,
        balance_after=entry.balance_after,
        ledger_reason=entry.reason,
    )
