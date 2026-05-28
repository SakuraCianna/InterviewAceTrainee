from email.headerregistry import Address
from html import escape
import logging
from math import ceil
from typing import Any, Callable, Protocol

import httpx
import resend

from app.core.config import Settings


logger = logging.getLogger("mianba.mailers")


class EmailDeliveryError(RuntimeError):
    """Raised when a configured email provider cannot deliver a message."""


class EmailSender(Protocol):
    def send_verification_code(self, to_email: str, code: str, expires_in_seconds: int) -> dict[str, Any]:
        ...


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
            logger.exception(
                "Resend delivery failed from_address=%s recipient_domain=%s error_type=%s provider_error=%s",
                self.from_address,
                extract_email_domain(to_email),
                type(exc).__name__,
                getattr(exc, "message", str(exc)),
            )
            raise EmailDeliveryError("resend_delivery_failed") from exc


class SendCloudEmailSender:
    def __init__(
        self,
        api_user: str,
        api_key: str,
        from_address: str,
        from_name: str,
        api_url: str = "https://api.sendcloud.net/apiv2/mail/send",
        post_form: Callable[[str, dict[str, str]], Any] | None = None,
    ) -> None:
        self.api_user = api_user
        self.api_key = api_key
        self.from_address = from_address
        self.from_name = from_name
        self.api_url = api_url
        self.post_form = post_form or self._post_form

    def send_verification_code(self, to_email: str, code: str, expires_in_seconds: int) -> dict[str, Any]:
        if not self.api_user:
            raise EmailDeliveryError("sendcloud_api_user_missing")
        if not self.api_key:
            raise EmailDeliveryError("sendcloud_api_key_missing")
        if not self.from_address:
            raise EmailDeliveryError("sendcloud_from_address_missing")

        message = build_verification_email(
            from_address=self.from_address,
            to_email=to_email,
            code=code,
            expires_in_seconds=expires_in_seconds,
        )
        payload = {
            "apiUser": self.api_user,
            "apiKey": self.api_key,
            "from": self.from_address,
            "fromName": self.from_name,
            "to": to_email,
            "subject": message["subject"],
            "html": message["html"],
            "plain": message["text"],
        }

        try:
            response = self.post_form(self.api_url, payload)
            response.raise_for_status()
            data = response.json()
        except Exception as exc:
            logger.exception(
                "SendCloud delivery request failed from_address=%s recipient_domain=%s error_type=%s provider_error=%s",
                self.from_address,
                extract_email_domain(to_email),
                type(exc).__name__,
                str(exc),
            )
            raise EmailDeliveryError("sendcloud_delivery_failed") from exc

        if not data.get("result"):
            logger.error(
                "SendCloud delivery rejected from_address=%s recipient_domain=%s status_code=%s provider_message=%s",
                self.from_address,
                extract_email_domain(to_email),
                data.get("statusCode"),
                data.get("message"),
            )
            raise EmailDeliveryError("sendcloud_delivery_failed")

        return {
            "provider": "sendcloud",
            "to": to_email,
            "statusCode": str(data.get("statusCode", "")),
            "message": str(data.get("message", "")),
        }

    @staticmethod
    def _post_form(url: str, data: dict[str, str]) -> httpx.Response:
        return httpx.post(url, data=data, timeout=10)


class RoutedEmailSender:
    def __init__(
        self,
        primary_sender: EmailSender,
        domestic_sender: EmailSender,
        domestic_domains: set[str],
    ) -> None:
        self.primary_sender = primary_sender
        self.domestic_sender = domestic_sender
        self.domestic_domains = domestic_domains

    def send_verification_code(self, to_email: str, code: str, expires_in_seconds: int) -> dict[str, Any]:
        if extract_email_domain(to_email) in self.domestic_domains:
            return self.domestic_sender.send_verification_code(to_email, code, expires_in_seconds)
        return self.primary_sender.send_verification_code(to_email, code, expires_in_seconds)


def extract_email_domain(email: str) -> str:
    parts = email.rsplit("@", 1)
    if len(parts) != 2 or not parts[1]:
        return "invalid"
    return parts[1].lower()


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
        "subject": "面霸练习生登录验证码",
        "html": (
            "<div style=\"margin:0;padding:0;background:#f4f7fb;color:#111827;"
            "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',Arial,sans-serif\">"
            "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
            "style=\"border-collapse:collapse;background:#f4f7fb;padding:0;margin:0\">"
            "<tr><td align=\"center\" style=\"padding:32px 16px\">"
            "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
            "style=\"border-collapse:collapse;max-width:560px;background:#ffffff;border:1px solid #e5eaf3;"
            "border-radius:18px;overflow:hidden;box-shadow:0 18px 48px rgba(17,24,39,0.08)\">"
            "<tr><td style=\"padding:28px 30px 18px;background:#101827;color:#ffffff\">"
            "<div style=\"font-size:13px;line-height:1.4;color:#c8ff5d;font-weight:800;"
            "letter-spacing:0.08em;text-transform:uppercase\">AI 面试训练平台</div>"
            "<h1 style=\"margin:12px 0 0;font-size:24px;line-height:1.35;font-weight:900\">"
            "邮箱验证码已生成</h1>"
            "<p style=\"margin:10px 0 0;color:#d7deea;font-size:14px;line-height:1.7\">"
            "正在验证面霸练习生账号, 请在当前页面完成输入</p>"
            "</td></tr>"
            "<tr><td style=\"padding:30px\">"
            "<p style=\"margin:0 0 14px;color:#4b5563;font-size:15px;line-height:1.8\">"
            "本次登录验证码为:</p>"
            "<div style=\"margin:0 0 22px;padding:20px 18px;background:#f7faff;border:1px solid #dbe7ff;"
            "border-radius:14px;text-align:center\">"
            f"<span style=\"font-family:'SFMono-Regular',Consolas,'Liberation Mono',monospace;"
            f"font-size:34px;line-height:1.2;font-weight:900;letter-spacing:8px;color:#2563eb\">{safe_code}</span>"
            "</div>"
            f"<p style=\"margin:0;color:#4b5563;font-size:14px;line-height:1.8\">"
            f"验证码将在 <strong style=\"color:#111827\">{minutes} 分钟</strong> 后失效, "
            "请勿转发或告知他人</p>"
            "<div style=\"margin-top:24px;padding:16px 18px;background:#fff7ed;border:1px solid #fed7aa;"
            "border-radius:14px\">"
            "<div style=\"font-size:14px;font-weight:900;color:#9a3412;margin-bottom:6px\">安全提示</div>"
            "<div style=\"font-size:13px;line-height:1.7;color:#7c2d12\">"
            "如果不是本人操作, 可以忽略本邮件账号不会因此受到影响</div>"
            "</div>"
            "<p style=\"margin:24px 0 0;color:#8a94a6;font-size:12px;line-height:1.7\">"
            "本邮件由系统自动发送, 请勿直接回复</p>"
            "</td></tr>"
            "</table>"
            "</td></tr>"
            "</table>"
            "</div>"
        ),
        "text": (
            f"面霸练习生登录验证码: {code}\n"
            f"验证码将在 {minutes} 分钟后失效, 请勿转发或告知他人\n"
            "如果不是本人操作, 可以忽略本邮件"
        ),
    }


def parse_email_domains(raw_domains: str) -> set[str]:
    return {domain.strip().lower() for domain in raw_domains.split(",") if domain.strip()}


def build_email_sender(settings: Settings) -> EmailSender:
    provider = settings.email_provider.strip().lower()
    if provider == "dev":
        primary_sender: EmailSender = DevEmailSender()
    elif provider == "resend":
        primary_sender = ResendEmailSender(
            api_key=settings.resend_api_key,
            from_address=settings.email_from_address,
            from_name=settings.email_from_name,
        )
    else:
        raise EmailDeliveryError("unsupported_email_provider")

    domestic_provider = settings.domestic_email_provider.strip().lower()
    if not domestic_provider:
        return primary_sender
    if domestic_provider != "sendcloud":
        raise EmailDeliveryError("unsupported_domestic_email_provider")

    domestic_sender = SendCloudEmailSender(
        api_user=settings.sendcloud_api_user,
        api_key=settings.sendcloud_api_key,
        from_address=settings.sendcloud_from_address or settings.email_from_address,
        from_name=settings.sendcloud_from_name or settings.email_from_name,
        api_url=settings.sendcloud_api_url,
    )
    return RoutedEmailSender(
        primary_sender=primary_sender,
        domestic_sender=domestic_sender,
        domestic_domains=parse_email_domains(settings.domestic_email_domains),
    )


def send_verification_code_email(settings: Settings, to_email: str, code: str, expires_in_seconds: int) -> dict[str, Any]:
    return build_email_sender(settings).send_verification_code(
        to_email=to_email,
        code=code,
        expires_in_seconds=expires_in_seconds,
    )
