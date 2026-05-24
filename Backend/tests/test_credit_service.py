import pytest

from app.services.credits import CreditLedger, InsufficientCreditsError


def test_regular_user_starting_interview_consumes_requested_credit_cost():
    ledger = CreditLedger(initial_balance=3, is_admin=False)
    entry = ledger.consume_for_interview("session-1", credit_cost=2, interview_type="ielts")

    assert entry.change_amount == -2
    assert entry.balance_after == 1
    assert entry.reason == "interview_start:ielts"
    assert entry.related_session_id == "session-1"


def test_admin_starting_interview_does_not_consume_credit():
    ledger = CreditLedger(initial_balance=0, is_admin=True)
    entry = ledger.consume_for_interview("session-1", credit_cost=3, interview_type="job")

    assert entry.change_amount == 0
    assert entry.balance_after == 0
    assert entry.reason == "admin_unlimited_interview"
    assert entry.related_session_id == "session-1"


def test_regular_user_cannot_start_interview_without_credit():
    ledger = CreditLedger(initial_balance=0, is_admin=False)

    with pytest.raises(InsufficientCreditsError):
        ledger.consume_for_interview("session-1", credit_cost=1, interview_type="job")


def test_regular_user_cannot_start_interview_when_balance_below_cost():
    ledger = CreditLedger(initial_balance=2, is_admin=False)

    with pytest.raises(InsufficientCreditsError):
        ledger.consume_for_interview("session-1", credit_cost=3, interview_type="job")
