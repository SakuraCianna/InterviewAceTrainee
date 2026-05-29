import unittest

from app.api.interviews import start_interview_with_stores
from app.schemas.interviews import InterviewStartRequest, InterviewType
from app.services.credit_balances import InMemoryCreditBalanceStore, _memory_balances
from app.services.credit_ledger import InMemoryCreditLedgerStore
from app.services.credits import InsufficientCreditsError
from app.services.interview_materials import InMemoryInterviewMaterialStore
from app.services.interview_runtime import InMemoryInterviewRuntimeStore
from app.services.interview_vouchers import InMemoryInterviewVoucherStore


class InterviewVoucherTests(unittest.TestCase):
    def setUp(self) -> None:
        _memory_balances.clear()

    def test_trial_voucher_is_redeemed_before_paid_credits(self) -> None:
        email = "new@example.com"
        _memory_balances[email] = 0
        voucher_store = InMemoryInterviewVoucherStore()
        voucher_store.issue(
            user_email=email,
            voucher_type="new_user_trial",
            issue_reason="registration_bonus",
        )

        response = start_interview_with_stores(
            payload=InterviewStartRequest(session_id="civil-trial", interview_type=InterviewType.CIVIL_SERVICE),
            claims={"sub": email, "role": "user", "session_id": "session"},
            credit_store=InMemoryCreditBalanceStore(),
            credit_ledger_store=InMemoryCreditLedgerStore(),
            voucher_store=voucher_store,
            interview_store=InMemoryInterviewRuntimeStore(),
            material_store=InMemoryInterviewMaterialStore(),
        )

        self.assertEqual(response.credit_change, 0)
        self.assertEqual(response.balance_after, 0)
        self.assertEqual(response.ledger_reason, "voucher_redeemed:civil_service")
        self.assertEqual(voucher_store.available_count(email), 0)

    def test_paid_interview_uses_weighted_credit_cost_after_voucher_is_used(self) -> None:
        email = "paid@example.com"
        _memory_balances[email] = 2

        response = start_interview_with_stores(
            payload=InterviewStartRequest(session_id="ielts-paid", interview_type=InterviewType.IELTS),
            claims={"sub": email, "role": "user", "session_id": "session"},
            credit_store=InMemoryCreditBalanceStore(),
            credit_ledger_store=InMemoryCreditLedgerStore(),
            voucher_store=InMemoryInterviewVoucherStore(),
            interview_store=InMemoryInterviewRuntimeStore(),
            material_store=InMemoryInterviewMaterialStore(),
        )

        self.assertEqual(response.credit_change, -2)
        self.assertEqual(response.balance_after, 0)
        self.assertEqual(response.ledger_reason, "interview_start:ielts")

    def test_insufficient_paid_credits_without_voucher_still_blocks_interview(self) -> None:
        email = "blocked@example.com"
        _memory_balances[email] = 1

        with self.assertRaises(InsufficientCreditsError):
            InMemoryCreditBalanceStore().adjust(email, -2)


if __name__ == "__main__":
    unittest.main()
