from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, Index, Integer, JSON, String, Text, Uuid
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


class InterviewVoucher(Base):
    __tablename__ = "interview_vouchers"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    user_email: Mapped[str] = mapped_column(String(255), index=True)
    voucher_type: Mapped[str] = mapped_column(String(80), default="admin_grant")
    scope_interview_type: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    remaining_uses: Mapped[int] = mapped_column(Integer, default=1)
    status: Mapped[str] = mapped_column(String(32), default="available", index=True)
    issue_reason: Mapped[str] = mapped_column(String(120), default="manual_grant")
    issued_by_admin_email: Mapped[str | None] = mapped_column(String(255), nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    redeemed_session_id: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    redeemed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class InterviewSession(Base):
    __tablename__ = "interview_sessions"
    __table_args__ = (
        Index("ix_interview_sessions_user_status_created", "user_email", "status", "created_at"),
        Index("ix_interview_sessions_user_created", "user_email", "created_at"),
    )

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
    target_school: Mapped[str | None] = mapped_column(String(160), nullable=True)
    major: Mapped[str | None] = mapped_column(String(160), nullable=True)
    research_direction: Mapped[str | None] = mapped_column(String(240), nullable=True)
    profile_summary: Mapped[str] = mapped_column(Text)
    keywords_json: Mapped[list[str]] = mapped_column(json_type, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class InterviewTurn(Base):
    __tablename__ = "interview_turns"
    __table_args__ = (Index("ix_interview_turns_session_turn", "session_id", "turn_index"),)

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
    __table_args__ = (Index("ix_interview_reports_session_created", "session_id", "created_at"),)

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
    __table_args__ = (Index("ix_ai_call_logs_created_at", "created_at"),)

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    session_id: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    provider_type: Mapped[str] = mapped_column(String(16))
    provider_name: Mapped[str] = mapped_column(String(80))
    model_name: Mapped[str] = mapped_column(String(120))
    purpose: Mapped[str] = mapped_column(String(80))
    success: Mapped[bool] = mapped_column(Boolean)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    provider_request_id: Mapped[str | None] = mapped_column(String(160), nullable=True)
    input_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    output_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    audio_duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    characters: Mapped[int | None] = mapped_column(Integer, nullable=True)
    estimated_cost_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    usage_json: Mapped[dict | None] = mapped_column(json_type, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class ContentSafetyLog(Base):
    __tablename__ = "content_safety_logs"
    __table_args__ = (Index("ix_content_safety_logs_created_at", "created_at"),)

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    user_email: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    session_id: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    source: Mapped[str] = mapped_column(String(32), index=True)
    action: Mapped[str] = mapped_column(String(40), index=True)
    risk_level: Mapped[str] = mapped_column(String(20), index=True)
    categories_json: Mapped[list[str]] = mapped_column(json_type, default=list)
    matched_terms_json: Mapped[list[str]] = mapped_column(json_type, default=list)
    content_excerpt: Mapped[str | None] = mapped_column(Text, nullable=True)
    message_code: Mapped[str | None] = mapped_column(String(80), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class AuthLoginLog(Base):
    __tablename__ = "auth_login_logs"
    __table_args__ = (
        Index("ix_auth_login_logs_created_at", "created_at"),
        Index("ix_auth_login_logs_email_created", "email", "created_at"),
    )

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    email: Mapped[str] = mapped_column(String(255), index=True)
    auth_method: Mapped[str] = mapped_column(String(40))
    role: Mapped[str] = mapped_column(String(32))
    success: Mapped[bool] = mapped_column(Boolean)
    failure_reason: Mapped[str | None] = mapped_column(String(120), nullable=True)
    ip_address: Mapped[str | None] = mapped_column(String(80), nullable=True)
    user_agent: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class CustomerServiceNote(Base):
    __tablename__ = "customer_service_notes"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    user_email: Mapped[str] = mapped_column(String(255), index=True)
    admin_email: Mapped[str] = mapped_column(String(255), index=True)
    category: Mapped[str] = mapped_column(String(80))
    content: Mapped[str] = mapped_column(Text)
    related_session_id: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)


class RefundCase(Base):
    __tablename__ = "refund_cases"

    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True, default=uuid_pk)
    user_email: Mapped[str] = mapped_column(String(255), index=True)
    status: Mapped[str] = mapped_column(String(32), index=True, default="open")
    reason: Mapped[str] = mapped_column(String(120))
    description: Mapped[str] = mapped_column(Text)
    amount_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    currency: Mapped[str] = mapped_column(String(16), default="CNY")
    credit_adjustment: Mapped[int | None] = mapped_column(Integer, nullable=True)
    related_session_id: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    resolution: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_by_admin_email: Mapped[str] = mapped_column(String(255))
    updated_by_admin_email: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, onupdate=utc_now)


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
