from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, Integer, JSON, String, Text, Uuid
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base

json_type = JSON().with_variant(JSONB, "postgresql")


def uuid_pk() -> str:
    return str(uuid4())


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str | None] = mapped_column(Text, nullable=True)
    role: Mapped[str] = mapped_column(String(32), default="user")
    credit_balance: Mapped[int] = mapped_column(Integer, default=0)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class CreditLedgerModel(Base):
    __tablename__ = "credit_ledger"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    user_email: Mapped[str] = mapped_column(String(255), index=True)
    change_amount: Mapped[int] = mapped_column(Integer)
    balance_after: Mapped[int] = mapped_column(Integer)
    reason: Mapped[str] = mapped_column(String(80))
    related_session_id: Mapped[str | None] = mapped_column(String(120), nullable=True)
    operator_admin_email: Mapped[str | None] = mapped_column(String(255), nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class InterviewSession(Base):
    __tablename__ = "interview_sessions"

    id: Mapped[str] = mapped_column(String(120), primary_key=True)
    user_email: Mapped[str] = mapped_column(String(255), index=True)
    interview_type: Mapped[str] = mapped_column(String(32), default="mixed")
    material_id: Mapped[str | None] = mapped_column(Uuid(as_uuid=False), nullable=True, index=True)
    status: Mapped[str] = mapped_column(String(32), default="created")
    current_step_index: Mapped[int] = mapped_column(Integer, default=0)
    total_steps: Mapped[int] = mapped_column(Integer, default=0)
    charged_credit: Mapped[bool] = mapped_column(Boolean, default=False)
    admin_unlimited_usage: Mapped[bool] = mapped_column(Boolean, default=False)
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    started_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class InterviewMaterial(Base):
    __tablename__ = "interview_materials"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    user_email: Mapped[str] = mapped_column(String(255), index=True)
    interview_type: Mapped[str] = mapped_column(String(32), index=True)
    resume_filename: Mapped[str | None] = mapped_column(String(255), nullable=True)
    resume_content_type: Mapped[str | None] = mapped_column(String(120), nullable=True)
    resume_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    job_title: Mapped[str | None] = mapped_column(String(160), nullable=True)
    job_requirements: Mapped[str | None] = mapped_column(Text, nullable=True)
    major: Mapped[str | None] = mapped_column(String(160), nullable=True)
    research_direction: Mapped[str | None] = mapped_column(String(240), nullable=True)
    profile_summary: Mapped[str] = mapped_column(Text)
    keywords_json: Mapped[list[str]] = mapped_column(json_type, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class InterviewTurn(Base):
    __tablename__ = "interview_turns"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    session_id: Mapped[str] = mapped_column(String(120), index=True)
    turn_index: Mapped[int] = mapped_column(Integer)
    round_name: Mapped[str] = mapped_column(String(80))
    question_text: Mapped[str] = mapped_column(Text)
    answer_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(32), default="waiting_answer")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)
    answered_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class InterviewReport(Base):
    __tablename__ = "interview_reports"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    session_id: Mapped[str] = mapped_column(String(120), index=True)
    total_score: Mapped[int] = mapped_column(Integer)
    report_json: Mapped[dict] = mapped_column(json_type)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class AIProviderConfigModel(Base):
    __tablename__ = "ai_provider_configs"

    id: Mapped[str] = mapped_column(String(80), primary_key=True)
    provider_type: Mapped[str] = mapped_column(String(16), index=True)
    provider_name: Mapped[str] = mapped_column(String(80))
    display_name: Mapped[str] = mapped_column(String(120))
    model_name: Mapped[str] = mapped_column(String(120))
    purpose: Mapped[str] = mapped_column(String(80), default="general")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    priority: Mapped[int] = mapped_column(Integer, default=100)
    region: Mapped[str] = mapped_column(String(16), default="cn")
    encrypted_api_key: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class SystemConfigModel(Base):
    __tablename__ = "system_configs"

    key: Mapped[str] = mapped_column(String(120), primary_key=True)
    value_json: Mapped[object] = mapped_column(json_type)
    description: Mapped[str] = mapped_column(Text, default="")
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, onupdate=utc_now)


class AICallLog(Base):
    __tablename__ = "ai_call_logs"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    session_id: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    provider_type: Mapped[str] = mapped_column(String(16))
    provider_name: Mapped[str] = mapped_column(String(80))
    model_name: Mapped[str] = mapped_column(String(120))
    purpose: Mapped[str] = mapped_column(String(80))
    success: Mapped[bool] = mapped_column(Boolean)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    usage_json: Mapped[dict | None] = mapped_column(json_type, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class AdminAuditLog(Base):
    __tablename__ = "admin_audit_logs"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    admin_email: Mapped[str] = mapped_column(String(255), index=True)
    action: Mapped[str] = mapped_column(String(80))
    target_type: Mapped[str] = mapped_column(String(80))
    target_id: Mapped[str] = mapped_column(String(120))
    before_snapshot: Mapped[dict | None] = mapped_column(json_type, nullable=True)
    after_snapshot: Mapped[dict | None] = mapped_column(json_type, nullable=True)
    ip_address: Mapped[str | None] = mapped_column(String(80), nullable=True)
    user_agent: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)
