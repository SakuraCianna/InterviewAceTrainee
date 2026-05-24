from dataclasses import dataclass


class InsufficientCreditsError(ValueError):
    """Raised when a regular user attempts to start an interview without credits."""


@dataclass(frozen=True)
class CreditLedgerEntry:
    change_amount: int
    balance_after: int
    reason: str
    related_session_id: str


class CreditLedger:
    def __init__(self, initial_balance: int, is_admin: bool) -> None:
        if initial_balance < 0:
            raise ValueError("initial balance cannot be negative")
        self._balance = initial_balance
        self._is_admin = is_admin

    def consume_for_interview(self, session_id: str, credit_cost: int = 1, interview_type: str = "interview") -> CreditLedgerEntry:
        if credit_cost <= 0:
            raise ValueError("credit cost must be positive")

        if self._is_admin:
            return CreditLedgerEntry(
                change_amount=0,
                balance_after=self._balance,
                reason="admin_unlimited_interview",
                related_session_id=session_id,
            )

        if self._balance < credit_cost:
            raise InsufficientCreditsError("not enough credits to start an interview")

        self._balance -= credit_cost
        return CreditLedgerEntry(
            change_amount=-credit_cost,
            balance_after=self._balance,
            reason=f"interview_start:{interview_type}",
            related_session_id=session_id,
        )
