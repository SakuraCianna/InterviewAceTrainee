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

    def consume_for_interview(self, session_id: str) -> CreditLedgerEntry:
        if self._is_admin:
            return CreditLedgerEntry(
                change_amount=0,
                balance_after=self._balance,
                reason="admin_unlimited_interview",
                related_session_id=session_id,
            )

        if self._balance <= 0:
            raise InsufficientCreditsError("not enough credits to start an interview")

        self._balance -= 1
        return CreditLedgerEntry(
            change_amount=-1,
            balance_after=self._balance,
            reason="interview_start",
            related_session_id=session_id,
        )

