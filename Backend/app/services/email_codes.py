from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from hashlib import sha256


class InvalidEmailCodeError(ValueError):
    """Raised when an email code is missing, expired, or incorrect."""


@dataclass(frozen=True)
class EmailCodeRecord:
    email: str
    code: str
    expires_at: datetime


class EmailCodeStore:
    def __init__(self) -> None:
        self._records: dict[str, EmailCodeRecord] = {}

    def issue_code(self, email: str, now: datetime | None = None) -> EmailCodeRecord:
        issued_at = now or datetime.now(UTC)
        seed = f"{email}:{issued_at:%Y%m%d%H%M}".encode("utf-8")
        code = str(int(sha256(seed).hexdigest(), 16) % 1_000_000).zfill(6)
        record = EmailCodeRecord(email=email, code=code, expires_at=issued_at + timedelta(minutes=10))
        self._records[email] = record
        return record

    def consume_code(self, email: str, code: str, now: datetime | None = None) -> None:
        checked_at = now or datetime.now(UTC)
        record = self._records.get(email)
        if record is None or record.code != code or record.expires_at < checked_at:
            raise InvalidEmailCodeError("invalid email verification code")
        del self._records[email]
