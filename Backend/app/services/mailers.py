from email.headerregistry import Address
from html import escape
from math import ceil
from typing import Any

import resend

from app.core.config import Settings


class EmailDeliveryError(RuntimeError):
    """Raised when a configured email provider cannot deliver a message."""


class DevEmailSender:
    def send_verification_code(self, to_email: str, code: str, expires_in_seconds: int) -> dict[str, str]:
        return {"provider": "dev", "to": to_email, "code": code, "expires_in_seconds": str(expires_in_seconds)}


class ResendEmailSender:
    def __init__(self, api_key: str, from_address: str, from_name: str) -> None:
        self.api_key = api_key
        self.from_address = from_address
        self.from_name = from_name

    def send_verification_code(self, to_email: str, code: str, expires_in_seconds: int) -> dict[str, Any]:
        if not self.api_key:
            raise EmailDeliveryError("resend_api_key_missing")
        if not self.from_address:
            raise EmailDeliveryError("email_from_address_missing")

        resend.api_key = self.api_key
        message = build_verification_email(
            from_address=format_sender(self.from_address, self.from_name),
            to_email=to_email,
            code=code,
            expires_in_seconds=expires_in_seconds,
        )

        try:
            return resend.Emails.send(message)
        except Exception as exc:  # Resend raises provider-specific HTTP/client exceptions.
            raise EmailDeliveryError("resend_delivery_failed") from exc


def format_sender(from_address: str, from_name: str) -> str:
    if not from_name:
        return from_address
    return str(Address(display_name=from_name, addr_spec=from_address))


def build_verification_email(from_address: str, to_email: str, code: str, expires_in_seconds: int) -> dict[str, str]:
    minutes = max(1, ceil(expires_in_seconds / 60))
    safe_code = escape(code)
    return {
        "from": from_address,
        "to": to_email,
        "subject": "面霸练习生邮箱验证码",
        "html": (
            "<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
            "line-height:1.7;color:#10141f;max-width:520px\">"
            "<h2 style=\"margin:0 0 14px\">面霸练习生邮箱验证码</h2>"
            "<p>你正在登录或验证面霸练习生账号，本次验证码为：</p>"
            f"<p style=\"font-size:28px;font-weight:800;letter-spacing:6px;margin:18px 0\">{safe_code}</p>"
            f"<p>验证码 {minutes} 分钟内有效。若非本人操作，请忽略本邮件。</p>"
            "<p style=\"color:#5c6677;font-size:13px;margin-top:24px\">"
            "本邮件仅用于账号安全验证，请勿转发给他人。"
            "</p>"
            "</div>"
        ),
        "text": f"面霸练习生邮箱验证码：{code}。验证码 {minutes} 分钟内有效。若非本人操作，请忽略本邮件。",
    }


def build_email_sender(settings: Settings) -> DevEmailSender | ResendEmailSender:
    provider = settings.email_provider.strip().lower()
    if provider == "dev":
        return DevEmailSender()
    if provider == "resend":
        return ResendEmailSender(
            api_key=settings.resend_api_key,
            from_address=settings.email_from_address,
            from_name=settings.email_from_name,
        )
    raise EmailDeliveryError("unsupported_email_provider")


def send_verification_code_email(settings: Settings, to_email: str, code: str, expires_in_seconds: int) -> dict[str, Any]:
    return build_email_sender(settings).send_verification_code(
        to_email=to_email,
        code=code,
        expires_in_seconds=expires_in_seconds,
    )
