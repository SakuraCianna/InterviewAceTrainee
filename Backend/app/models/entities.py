from datetime import datetime
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


def uuid_pk() -> str:
    return str(uuid4())


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=uuid_pk)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str | None] = mapped_column(Text, nullable=True)
    role: Mapped[str] = mapped_column(String(32), default="user")
    credit_balance: Mapped[int] = mapped_column(Integer, default=0)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class CreditLedgerModel(Base):
    __tablename__ = "credit_ledger"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=uuid_pk)
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), index=True)
    change_amount: Mapped[int] = mapped_column(Integer)
    balance_after: Mapped[int] = mapped_column(Integer)
    reason: Mapped[str] = mapped_column(String(80))
    related_session_id: Mapped[str | None] = mapped_column(UUID(as_uuid=False), nullable=True)
    operator_admin_id: Mapped[str | None] = mapped_column(UUID(as_uuid=False), nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class InterviewSession(Base):
    __tablename__ = "interview_sessions"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=uuid_pk)
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), index=True)
    interview_type: Mapped[str] = mapped_column(String(32), default="mixed")
    status: Mapped[str] = mapped_column(String(32), default="created")
    charged_credit: Mapped[bool] = mapped_column(Boolean, default=False)
    admin_unlimited_usage: Mapped[bool] = mapped_column(Boolean, default=False)
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    started_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class InterviewReport(Base):
    __tablename__ = "interview_reports"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=uuid_pk)
    session_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("interview_sessions.id"), index=True)
    total_score: Mapped[int] = mapped_column(Integer)
    report_json: Mapped[dict] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class AIProviderConfigModel(Base):
    __tablename__ = "ai_provider_configs"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=uuid_pk)
    provider_type: Mapped[str] = mapped_column(String(16), index=True)
    provider_name: Mapped[str] = mapped_column(String(80))
    display_name: Mapped[str] = mapped_column(String(120))
    model_name: Mapped[str] = mapped_column(String(120))
    purpose: Mapped[str] = mapped_column(String(80), default="general")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    priority: Mapped[int] = mapped_column(Integer, default=100)
    region: Mapped[str] = mapped_column(String(16), default="cn")
    encrypted_api_key: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class AICallLog(Base):
    __tablename__ = "ai_call_logs"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=uuid_pk)
    session_id: Mapped[str | None] = mapped_column(UUID(as_uuid=False), nullable=True, index=True)
    provider_type: Mapped[str] = mapped_column(String(16))
    provider_name: Mapped[str] = mapped_column(String(80))
    model_name: Mapped[str] = mapped_column(String(120))
    purpose: Mapped[str] = mapped_column(String(80))
    success: Mapped[bool] = mapped_column(Boolean)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    usage_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class AdminAuditLog(Base):
    __tablename__ = "admin_audit_logs"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=uuid_pk)
    admin_user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), index=True)
    action: Mapped[str] = mapped_column(String(80))
    target_type: Mapped[str] = mapped_column(String(80))
    target_id: Mapped[str] = mapped_column(String(120))
    before_snapshot: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    after_snapshot: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    ip_address: Mapped[str | None] = mapped_column(String(80), nullable=True)
    user_agent: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

