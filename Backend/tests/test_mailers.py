import unittest

from app.services.mailers import (
    EmailDeliveryError,
    RoutedEmailSender,
    SendCloudEmailSender,
    extract_email_domain,
)


class FakeEmailSender:
    def __init__(self, provider: str) -> None:
        self.provider = provider
        self.sent_to: list[str] = []

    def send_verification_code(self, to_email: str, code: str, expires_in_seconds: int) -> dict[str, str]:
        self.sent_to.append(to_email)
        return {"provider": self.provider, "to": to_email, "code": code}


class FakeResponse:
    def __init__(self, payload: dict[str, object], status_error: Exception | None = None) -> None:
        self.payload = payload
        self.status_error = status_error

    def raise_for_status(self) -> None:
        if self.status_error is not None:
            raise self.status_error

    def json(self) -> dict[str, object]:
        return self.payload


class MailerTests(unittest.TestCase):
    def test_extract_email_domain_normalizes_domain(self) -> None:
        self.assertEqual(extract_email_domain("User@QQ.COM"), "qq.com")

    def test_routed_sender_uses_sendcloud_for_domestic_domain(self) -> None:
        domestic_sender = FakeEmailSender("sendcloud")
        primary_sender = FakeEmailSender("resend")
        sender = RoutedEmailSender(
            primary_sender=primary_sender,
            domestic_sender=domestic_sender,
            domestic_domains={"qq.com", "foxmail.com"},
        )

        result = sender.send_verification_code("student@qq.com", "123456", 300)

        self.assertEqual(result["provider"], "sendcloud")
        self.assertEqual(domestic_sender.sent_to, ["student@qq.com"])
        self.assertEqual(primary_sender.sent_to, [])

    def test_routed_sender_uses_resend_for_foreign_domain(self) -> None:
        domestic_sender = FakeEmailSender("sendcloud")
        primary_sender = FakeEmailSender("resend")
        sender = RoutedEmailSender(
            primary_sender=primary_sender,
            domestic_sender=domestic_sender,
            domestic_domains={"qq.com", "foxmail.com"},
        )

        result = sender.send_verification_code("student@gmail.com", "123456", 300)

        self.assertEqual(result["provider"], "resend")
        self.assertEqual(primary_sender.sent_to, ["student@gmail.com"])
        self.assertEqual(domestic_sender.sent_to, [])

    def test_sendcloud_sender_posts_expected_payload(self) -> None:
        calls: list[tuple[str, dict[str, str]]] = []

        def post_form(url: str, data: dict[str, str]) -> FakeResponse:
            calls.append((url, data))
            return FakeResponse({"result": True, "statusCode": 200})

        sender = SendCloudEmailSender(
            api_user="sakuracianna",
            api_key="secret",
            from_address="no-reply@mail.sakuracianna.icu",
            from_name="面霸练习生",
            post_form=post_form,
        )

        result = sender.send_verification_code("student@qq.com", "123456", 300)

        self.assertEqual(result["provider"], "sendcloud")
        self.assertEqual(len(calls), 1)
        _, payload = calls[0]
        self.assertEqual(payload["apiUser"], "sakuracianna")
        self.assertEqual(payload["from"], "no-reply@mail.sakuracianna.icu")
        self.assertEqual(payload["fromName"], "面霸练习生")
        self.assertEqual(payload["to"], "student@qq.com")
        self.assertIn("123456", payload["html"])
        self.assertIn("123456", payload["plain"])

    def test_sendcloud_sender_raises_on_provider_failure(self) -> None:
        def post_form(url: str, data: dict[str, str]) -> FakeResponse:
            return FakeResponse({"result": False, "statusCode": 400, "message": "bad request"})

        sender = SendCloudEmailSender(
            api_user="sakuracianna",
            api_key="secret",
            from_address="no-reply@mail.sakuracianna.icu",
            from_name="面霸练习生",
            post_form=post_form,
        )

        with self.assertRaisesRegex(EmailDeliveryError, "sendcloud_delivery_failed"):
            sender.send_verification_code("student@qq.com", "123456", 300)


if __name__ == "__main__":
    unittest.main()
