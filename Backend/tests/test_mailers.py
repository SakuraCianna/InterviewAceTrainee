import pytest

from app.core.config import Settings
from app.services.mailers import (
    EmailDeliveryError,
    ResendEmailSender,
    build_email_sender,
    build_verification_email,
    format_sender,
)


def test_format_sender_keeps_chinese_display_name():
    sender = format_sender("no-reply@example.com", "面霸练习生")

    assert sender == "面霸练习生 <no-reply@example.com>"


def test_build_verification_email_contains_code_and_expiration():
    message = build_verification_email(
        from_address="面霸练习生 <no-reply@example.com>",
        to_email="student@example.com",
        code="123456",
        expires_in_seconds=600,
    )

    assert message["from"] == "面霸练习生 <no-reply@example.com>"
    assert message["to"] == "student@example.com"
    assert "123456" in message["html"]
    assert "10 分钟" in message["text"]


def test_resend_email_sender_calls_resend(monkeypatch):
    sent_messages = []

    class FakeEmails:
        @staticmethod
        def send(message):
            sent_messages.append(message)
            return {"id": "email_123"}

    monkeypatch.setattr("app.services.mailers.resend.Emails", FakeEmails)

    sender = ResendEmailSender(
        api_key="re_test",
        from_address="no-reply@example.com",
        from_name="面霸练习生",
    )
    result = sender.send_verification_code("student@example.com", "123456", 300)

    assert result == {"id": "email_123"}
    assert sent_messages[0]["to"] == "student@example.com"
    assert sent_messages[0]["subject"] == "面霸练习生邮箱验证码"


def test_resend_email_sender_requires_api_key():
    sender = ResendEmailSender(api_key="", from_address="no-reply@example.com", from_name="面霸练习生")

    with pytest.raises(EmailDeliveryError, match="resend_api_key_missing"):
        sender.send_verification_code("student@example.com", "123456", 300)


def test_build_email_sender_rejects_unknown_provider():
    settings = Settings(email_provider="unknown")

    with pytest.raises(EmailDeliveryError, match="unsupported_email_provider"):
        build_email_sender(settings)
