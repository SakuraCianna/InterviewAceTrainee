import unittest

from app.api.admin import issue_vouchers_with_stores, resolve_voucher_recipients
from app.schemas.admin import AdminVoucherIssueRequest
from app.services.audit_logs import InMemoryAuditLogStore
from app.services.interview_vouchers import InMemoryInterviewVoucherStore
from app.services.user_credentials import InMemoryUserCredentialStore, _memory_users


class AdminVoucherTests(unittest.TestCase):
    def setUp(self) -> None:
        _memory_users.clear()

    def test_admin_can_issue_vouchers_to_selected_users(self) -> None:
        user_store = InMemoryUserCredentialStore()
        user_store.create_placeholder_user("one@example.com")
        user_store.create_placeholder_user("two@example.com")
        voucher_store = InMemoryInterviewVoucherStore()
        payload = AdminVoucherIssueRequest(
            user_emails=["one@example.com", "two@example.com"],
            quantity=2,
            reason="campaign_trial",
        )

        response = issue_vouchers_with_stores(
            payload=payload,
            admin_email="admin@example.com",
            target_emails=resolve_voucher_recipients(payload, user_store, None),
            voucher_store=voucher_store,
            audit_store=InMemoryAuditLogStore(),
            ip_address=None,
            user_agent=None,
        )

        self.assertEqual(response.total_recipients, 2)
        self.assertEqual(response.total_vouchers, 4)
        self.assertEqual(voucher_store.available_count("one@example.com"), 2)
        self.assertEqual(voucher_store.available_count("two@example.com"), 2)

    def test_admin_can_resolve_all_active_users_for_voucher_issue(self) -> None:
        user_store = InMemoryUserCredentialStore()
        user_store.create_placeholder_user("active@example.com")
        user_store.create_placeholder_user("disabled@example.com")
        user_store.set_active("disabled@example.com", False)
        payload = AdminVoucherIssueRequest(issue_all_active_users=True, quantity=1, reason="launch_bonus")

        recipients = resolve_voucher_recipients(payload, user_store, None)

        self.assertEqual(recipients, ["active@example.com"])


if __name__ == "__main__":
    unittest.main()
