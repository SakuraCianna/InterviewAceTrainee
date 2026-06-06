"""final initial schema

Revision ID: 20260528_0002
Revises:
Create Date: 2026-05-28
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260528_0002"
down_revision = None
branch_labels = None
depends_on = None

DEFAULT_ADMIN_ID = "75451592-2000-4000-8000-000000000001"
DEFAULT_ADMIN_EMAIL = "754515922@qq.com"
DEFAULT_ADMIN_PASSWORD_HASH = "$argon2id$v=19$m=65536,t=3,p=4$nTC5ihZxqGcWiJ3pa0D+nA$qk6WeU0Pi5I+4Cb0/w7VdbcL1dGEQqHIA1dHIw4QCuY"


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
    op.execute(
        f"""
        INSERT INTO users (id, email, password_hash, role, credit_balance, is_active, created_at)
        VALUES ('{DEFAULT_ADMIN_ID}', '{DEFAULT_ADMIN_EMAIL}', '{DEFAULT_ADMIN_PASSWORD_HASH}', 'admin', 0, TRUE, NOW())
        ON CONFLICT (email) DO UPDATE
        SET password_hash = EXCLUDED.password_hash,
            role = 'admin',
            is_active = TRUE
        """
    )

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
        "interview_vouchers",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("user_email", sa.String(length=255), nullable=False),
        sa.Column("voucher_type", sa.String(length=80), nullable=False),
        sa.Column("scope_interview_type", sa.String(length=32), nullable=True),
        sa.Column("remaining_uses", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("issue_reason", sa.String(length=120), nullable=False),
        sa.Column("issued_by_admin_email", sa.String(length=255), nullable=True),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("redeemed_session_id", sa.String(length=120), nullable=True),
        sa.Column("redeemed_at", sa.DateTime(), nullable=True),
        sa.Column("expires_at", sa.DateTime(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_interview_vouchers_user_email", "interview_vouchers", ["user_email"])
    op.create_index("ix_interview_vouchers_scope_interview_type", "interview_vouchers", ["scope_interview_type"])
    op.create_index("ix_interview_vouchers_status", "interview_vouchers", ["status"])
    op.create_index("ix_interview_vouchers_redeemed_session_id", "interview_vouchers", ["redeemed_session_id"])

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
    op.create_index(
        "ix_interview_sessions_user_status_created",
        "interview_sessions",
        ["user_email", "status", "created_at"],
    )
    op.create_index("ix_interview_sessions_user_created", "interview_sessions", ["user_email", "created_at"])

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
        sa.Column("target_school", sa.String(length=160), nullable=True),
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
    op.create_index("ix_interview_turns_session_turn", "interview_turns", ["session_id", "turn_index"])

    op.create_table(
        "interview_reports",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("session_id", sa.String(length=120), nullable=False),
        sa.Column("total_score", sa.Integer(), nullable=False),
        sa.Column("report_json", postgresql.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_interview_reports_session_id", "interview_reports", ["session_id"])
    op.create_index("ix_interview_reports_session_created", "interview_reports", ["session_id", "created_at"])

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

    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS interview_capability_vectors (
            id VARCHAR(80) PRIMARY KEY,
            card_id VARCHAR(160) NOT NULL,
            title VARCHAR(240) NOT NULL,
            interview_types JSONB NOT NULL,
            content_hash VARCHAR(64) NOT NULL,
            embedding_model VARCHAR(160) NOT NULL,
            embedding_dimensions INTEGER NOT NULL,
            source_text TEXT NOT NULL,
            metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
            embedding vector NOT NULL,
            status VARCHAR(32) NOT NULL DEFAULT 'ready',
            created_at TIMESTAMP NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMP NOT NULL DEFAULT NOW()
        )
        """
    )
    op.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS ux_interview_capability_vectors_card_model
        ON interview_capability_vectors (card_id, embedding_model)
        """
    )
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS ix_interview_capability_vectors_model_status
        ON interview_capability_vectors (embedding_model, status)
        """
    )
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS ix_interview_capability_vectors_interview_types
        ON interview_capability_vectors USING GIN (interview_types)
        """
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
        sa.Column("provider_request_id", sa.String(length=160), nullable=True),
        sa.Column("input_tokens", sa.Integer(), nullable=True),
        sa.Column("output_tokens", sa.Integer(), nullable=True),
        sa.Column("audio_duration_ms", sa.Integer(), nullable=True),
        sa.Column("characters", sa.Integer(), nullable=True),
        sa.Column("estimated_cost_cents", sa.Integer(), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("usage_json", postgresql.JSONB(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_ai_call_logs_session_id", "ai_call_logs", ["session_id"])
    op.create_index("ix_ai_call_logs_created_at", "ai_call_logs", ["created_at"])

    op.create_table(
        "content_safety_logs",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("user_email", sa.String(length=255), nullable=True),
        sa.Column("session_id", sa.String(length=120), nullable=True),
        sa.Column("source", sa.String(length=32), nullable=False),
        sa.Column("action", sa.String(length=40), nullable=False),
        sa.Column("risk_level", sa.String(length=20), nullable=False),
        sa.Column("categories_json", postgresql.JSONB(), nullable=False),
        sa.Column("matched_terms_json", postgresql.JSONB(), nullable=False),
        sa.Column("content_excerpt", sa.Text(), nullable=True),
        sa.Column("message_code", sa.String(length=80), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_content_safety_logs_user_email", "content_safety_logs", ["user_email"])
    op.create_index("ix_content_safety_logs_session_id", "content_safety_logs", ["session_id"])
    op.create_index("ix_content_safety_logs_source", "content_safety_logs", ["source"])
    op.create_index("ix_content_safety_logs_action", "content_safety_logs", ["action"])
    op.create_index("ix_content_safety_logs_risk_level", "content_safety_logs", ["risk_level"])
    op.create_index("ix_content_safety_logs_created_at", "content_safety_logs", ["created_at"])

    op.create_table(
        "auth_login_logs",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("auth_method", sa.String(length=40), nullable=False),
        sa.Column("role", sa.String(length=32), nullable=False),
        sa.Column("success", sa.Boolean(), nullable=False),
        sa.Column("failure_reason", sa.String(length=120), nullable=True),
        sa.Column("ip_address", sa.String(length=80), nullable=True),
        sa.Column("user_agent", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_auth_login_logs_email", "auth_login_logs", ["email"])
    op.create_index("ix_auth_login_logs_created_at", "auth_login_logs", ["created_at"])
    op.create_index("ix_auth_login_logs_email_created", "auth_login_logs", ["email", "created_at"])

    op.create_table(
        "customer_service_notes",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("user_email", sa.String(length=255), nullable=False),
        sa.Column("admin_email", sa.String(length=255), nullable=False),
        sa.Column("category", sa.String(length=80), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("related_session_id", sa.String(length=120), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_customer_service_notes_user_email", "customer_service_notes", ["user_email"])
    op.create_index("ix_customer_service_notes_admin_email", "customer_service_notes", ["admin_email"])
    op.create_index("ix_customer_service_notes_related_session_id", "customer_service_notes", ["related_session_id"])

    op.create_table(
        "refund_cases",
        sa.Column("id", postgresql.UUID(as_uuid=False), primary_key=True),
        sa.Column("user_email", sa.String(length=255), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("reason", sa.String(length=120), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("amount_cents", sa.Integer(), nullable=True),
        sa.Column("currency", sa.String(length=16), nullable=False),
        sa.Column("credit_adjustment", sa.Integer(), nullable=True),
        sa.Column("related_session_id", sa.String(length=120), nullable=True),
        sa.Column("resolution", sa.Text(), nullable=True),
        sa.Column("created_by_admin_email", sa.String(length=255), nullable=False),
        sa.Column("updated_by_admin_email", sa.String(length=255), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_refund_cases_user_email", "refund_cases", ["user_email"])
    op.create_index("ix_refund_cases_status", "refund_cases", ["status"])
    op.create_index("ix_refund_cases_related_session_id", "refund_cases", ["related_session_id"])

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
    op.execute("DROP TABLE IF EXISTS interview_capability_vectors")
    op.drop_index("ix_admin_audit_logs_admin_email", table_name="admin_audit_logs")
    op.drop_table("admin_audit_logs")
    op.drop_index("ix_refund_cases_related_session_id", table_name="refund_cases")
    op.drop_index("ix_refund_cases_status", table_name="refund_cases")
    op.drop_index("ix_refund_cases_user_email", table_name="refund_cases")
    op.drop_table("refund_cases")
    op.drop_index("ix_customer_service_notes_related_session_id", table_name="customer_service_notes")
    op.drop_index("ix_customer_service_notes_admin_email", table_name="customer_service_notes")
    op.drop_index("ix_customer_service_notes_user_email", table_name="customer_service_notes")
    op.drop_table("customer_service_notes")
    op.drop_index("ix_auth_login_logs_email_created", table_name="auth_login_logs")
    op.drop_index("ix_auth_login_logs_created_at", table_name="auth_login_logs")
    op.drop_index("ix_auth_login_logs_email", table_name="auth_login_logs")
    op.drop_table("auth_login_logs")
    op.drop_index("ix_content_safety_logs_created_at", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_risk_level", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_action", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_source", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_session_id", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_user_email", table_name="content_safety_logs")
    op.drop_table("content_safety_logs")
    op.drop_index("ix_ai_call_logs_created_at", table_name="ai_call_logs")
    op.drop_index("ix_ai_call_logs_session_id", table_name="ai_call_logs")
    op.drop_table("ai_call_logs")
    op.drop_table("system_configs")
    op.drop_index("ix_ai_provider_configs_provider_type", table_name="ai_provider_configs")
    op.drop_table("ai_provider_configs")
    op.drop_index("ix_interview_reports_session_created", table_name="interview_reports")
    op.drop_index("ix_interview_reports_session_id", table_name="interview_reports")
    op.drop_table("interview_reports")
    op.drop_index("ix_interview_turns_session_turn", table_name="interview_turns")
    op.drop_index("ix_interview_turns_session_id", table_name="interview_turns")
    op.drop_table("interview_turns")
    op.drop_index("ix_interview_materials_interview_type", table_name="interview_materials")
    op.drop_index("ix_interview_materials_user_email", table_name="interview_materials")
    op.drop_table("interview_materials")
    op.drop_index("ix_interview_sessions_user_created", table_name="interview_sessions")
    op.drop_index("ix_interview_sessions_user_status_created", table_name="interview_sessions")
    op.drop_index("ix_interview_sessions_material_id", table_name="interview_sessions")
    op.drop_index("ix_interview_sessions_user_email", table_name="interview_sessions")
    op.drop_table("interview_sessions")
    op.drop_index("ix_credit_ledger_user_email", table_name="credit_ledger")
    op.drop_table("credit_ledger")
    op.drop_index("ix_interview_vouchers_redeemed_session_id", table_name="interview_vouchers")
    op.drop_index("ix_interview_vouchers_status", table_name="interview_vouchers")
    op.drop_index("ix_interview_vouchers_scope_interview_type", table_name="interview_vouchers")
    op.drop_index("ix_interview_vouchers_user_email", table_name="interview_vouchers")
    op.drop_table("interview_vouchers")
    op.drop_index("ix_users_email", table_name="users")
    op.drop_table("users")
