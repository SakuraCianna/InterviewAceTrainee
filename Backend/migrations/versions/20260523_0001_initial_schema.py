"""initial schema

Revision ID: 20260523_0001
Revises:
Create Date: 2026-05-23
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260523_0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("password_hash", sa.Text(), nullable=True),
        sa.Column("role", sa.String(length=32), nullable=False),
        sa.Column("credit_balance", sa.Integer(), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)

    op.create_table(
        "credit_ledger",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("user_email", sa.String(length=255), nullable=False),
        sa.Column("change_amount", sa.Integer(), nullable=False),
        sa.Column("balance_after", sa.Integer(), nullable=False),
        sa.Column("reason", sa.String(length=80), nullable=False),
        sa.Column("related_session_id", sa.String(length=120), nullable=True),
        sa.Column("operator_admin_email", sa.String(length=255), nullable=True),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_credit_ledger_user_email", "credit_ledger", ["user_email"])

    op.create_table(
        "interview_sessions",
        sa.Column("id", sa.String(length=120), primary_key=True),
        sa.Column("user_email", sa.String(length=255), nullable=False),
        sa.Column("interview_type", sa.String(length=32), nullable=False),
        sa.Column("material_id", postgresql.UUID(as_uuid=False), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("current_step_index", sa.Integer(), nullable=False),
        sa.Column("total_steps", sa.Integer(), nullable=False),
        sa.Column("charged_credit", sa.Boolean(), nullable=False),
        sa.Column("admin_unlimited_usage", sa.Boolean(), nullable=False),
        sa.Column("failure_reason", sa.Text(), nullable=True),
        sa.Column("started_at", sa.DateTime(), nullable=True),
        sa.Column("ended_at", sa.DateTime(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_interview_sessions_user_email", "interview_sessions", ["user_email"])
    op.create_index("ix_interview_sessions_material_id", "interview_sessions", ["material_id"])

    op.create_table(
        "interview_materials",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("user_email", sa.String(length=255), nullable=False),
        sa.Column("interview_type", sa.String(length=32), nullable=False),
        sa.Column("resume_filename", sa.String(length=255), nullable=True),
        sa.Column("resume_content_type", sa.String(length=120), nullable=True),
        sa.Column("resume_text", sa.Text(), nullable=True),
        sa.Column("job_title", sa.String(length=160), nullable=True),
        sa.Column("job_requirements", sa.Text(), nullable=True),
        sa.Column("major", sa.String(length=160), nullable=True),
        sa.Column("research_direction", sa.String(length=240), nullable=True),
        sa.Column("profile_summary", sa.Text(), nullable=False),
        sa.Column("keywords_json", postgresql.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_interview_materials_user_email", "interview_materials", ["user_email"])
    op.create_index("ix_interview_materials_interview_type", "interview_materials", ["interview_type"])

    op.create_table(
        "interview_turns",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("session_id", sa.String(length=120), nullable=False),
        sa.Column("turn_index", sa.Integer(), nullable=False),
        sa.Column("round_name", sa.String(length=80), nullable=False),
        sa.Column("question_text", sa.Text(), nullable=False),
        sa.Column("answer_text", sa.Text(), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("answered_at", sa.DateTime(), nullable=True),
    )
    op.create_index("ix_interview_turns_session_id", "interview_turns", ["session_id"])

    op.create_table(
        "interview_reports",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("session_id", sa.String(length=120), nullable=False),
        sa.Column("total_score", sa.Integer(), nullable=False),
        sa.Column("report_json", postgresql.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_interview_reports_session_id", "interview_reports", ["session_id"])

    op.create_table(
        "ai_provider_configs",
        sa.Column("id", sa.String(length=80), primary_key=True),
        sa.Column("provider_type", sa.String(length=16), nullable=False),
        sa.Column("provider_name", sa.String(length=80), nullable=False),
        sa.Column("display_name", sa.String(length=120), nullable=False),
        sa.Column("model_name", sa.String(length=120), nullable=False),
        sa.Column("purpose", sa.String(length=80), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False),
        sa.Column("priority", sa.Integer(), nullable=False),
        sa.Column("region", sa.String(length=16), nullable=False),
        sa.Column("encrypted_api_key", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_ai_provider_configs_provider_type", "ai_provider_configs", ["provider_type"])

    op.create_table(
        "system_configs",
        sa.Column("key", sa.String(length=120), primary_key=True),
        sa.Column("value_json", postgresql.JSONB(), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
    )

    op.create_table(
        "ai_call_logs",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("session_id", sa.String(length=120), nullable=True),
        sa.Column("provider_type", sa.String(length=16), nullable=False),
        sa.Column("provider_name", sa.String(length=80), nullable=False),
        sa.Column("model_name", sa.String(length=120), nullable=False),
        sa.Column("purpose", sa.String(length=80), nullable=False),
        sa.Column("success", sa.Boolean(), nullable=False),
        sa.Column("latency_ms", sa.Integer(), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("usage_json", postgresql.JSONB(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_ai_call_logs_session_id", "ai_call_logs", ["session_id"])

    op.create_table(
        "admin_audit_logs",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("admin_email", sa.String(length=255), nullable=False),
        sa.Column("action", sa.String(length=80), nullable=False),
        sa.Column("target_type", sa.String(length=80), nullable=False),
        sa.Column("target_id", sa.String(length=120), nullable=False),
        sa.Column("before_snapshot", postgresql.JSONB(), nullable=True),
        sa.Column("after_snapshot", postgresql.JSONB(), nullable=True),
        sa.Column("ip_address", sa.String(length=80), nullable=True),
        sa.Column("user_agent", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_admin_audit_logs_admin_email", "admin_audit_logs", ["admin_email"])


def downgrade() -> None:
    op.drop_index("ix_admin_audit_logs_admin_email", table_name="admin_audit_logs")
    op.drop_table("admin_audit_logs")
    op.drop_index("ix_ai_call_logs_session_id", table_name="ai_call_logs")
    op.drop_table("ai_call_logs")
    op.drop_table("system_configs")
    op.drop_index("ix_ai_provider_configs_provider_type", table_name="ai_provider_configs")
    op.drop_table("ai_provider_configs")
    op.drop_index("ix_interview_reports_session_id", table_name="interview_reports")
    op.drop_table("interview_reports")
    op.drop_index("ix_interview_turns_session_id", table_name="interview_turns")
    op.drop_table("interview_turns")
    op.drop_index("ix_interview_materials_interview_type", table_name="interview_materials")
    op.drop_index("ix_interview_materials_user_email", table_name="interview_materials")
    op.drop_table("interview_materials")
    op.drop_index("ix_interview_sessions_material_id", table_name="interview_sessions")
    op.drop_index("ix_interview_sessions_user_email", table_name="interview_sessions")
    op.drop_table("interview_sessions")
    op.drop_index("ix_credit_ledger_user_email", table_name="credit_ledger")
    op.drop_table("credit_ledger")
    op.drop_index("ix_users_email", table_name="users")
    op.drop_table("users")
