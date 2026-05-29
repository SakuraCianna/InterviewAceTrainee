import unittest
from unittest.mock import patch

from starlette.requests import Request

from app.api import auth
from app.core.config import Settings
from app.services.email_codes import EmailCodeRateLimitError


def build_request(ip: str) -> Request:
    return Request(
        {
            "type": "http",
            "method": "POST",
            "path": "/api/auth/email-code/request",
            "headers": [],
            "client": (ip, 12345),
        }
    )


class EmailRateLimitTests(unittest.TestCase):
    def setUp(self) -> None:
        self.redis_patch = patch("app.api.auth.get_redis_client", return_value=None)
        self.redis_patch.start()
        auth._email_code_limiter._requests.clear()

    def tearDown(self) -> None:
        auth._email_code_limiter._requests.clear()
        self.redis_patch.stop()

    def test_email_code_requests_are_limited_by_ip(self) -> None:
        settings = Settings(
            email_code_rate_limit=99,
            email_code_ip_rate_limit=1,
            email_code_domain_rate_limit=99,
        )
        request = build_request("203.0.113.10")

        auth.check_email_code_rate_limit("one@example.com", request=request, settings=settings)

        with self.assertRaises(EmailCodeRateLimitError):
            auth.check_email_code_rate_limit("two@example.com", request=request, settings=settings)

    def test_email_code_requests_are_limited_by_domain(self) -> None:
        settings = Settings(
            email_code_rate_limit=99,
            email_code_ip_rate_limit=99,
            email_code_domain_rate_limit=1,
        )

        auth.check_email_code_rate_limit("one@qq.com", request=build_request("203.0.113.10"), settings=settings)

        with self.assertRaises(EmailCodeRateLimitError):
            auth.check_email_code_rate_limit("two@qq.com", request=build_request("203.0.113.11"), settings=settings)


if __name__ == "__main__":
    unittest.main()
