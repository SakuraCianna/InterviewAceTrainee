"""add capacity indexes

Revision ID: 20260604_0003
Revises: 20260528_0002
Create Date: 2026-06-04
"""

from alembic import op

revision = "20260604_0003"
down_revision = "20260528_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index(
        "ix_interview_sessions_user_status_created",
        "interview_sessions",
        ["user_email", "status", "created_at"],
    )
    op.create_index(
        "ix_interview_sessions_user_created",
        "interview_sessions",
        ["user_email", "created_at"],
    )
    op.create_index("ix_interview_turns_session_turn", "interview_turns", ["session_id", "turn_index"])
    op.create_index("ix_interview_reports_session_created", "interview_reports", ["session_id", "created_at"])
    op.create_index("ix_ai_call_logs_created_at", "ai_call_logs", ["created_at"])
    op.create_index("ix_content_safety_logs_created_at", "content_safety_logs", ["created_at"])
    op.create_index("ix_auth_login_logs_created_at", "auth_login_logs", ["created_at"])
    op.create_index("ix_auth_login_logs_email_created", "auth_login_logs", ["email", "created_at"])


def downgrade() -> None:
    op.drop_index("ix_auth_login_logs_email_created", table_name="auth_login_logs")
    op.drop_index("ix_auth_login_logs_created_at", table_name="auth_login_logs")
    op.drop_index("ix_content_safety_logs_created_at", table_name="content_safety_logs")
    op.drop_index("ix_ai_call_logs_created_at", table_name="ai_call_logs")
    op.drop_index("ix_interview_reports_session_created", table_name="interview_reports")
    op.drop_index("ix_interview_turns_session_turn", table_name="interview_turns")
    op.drop_index("ix_interview_sessions_user_created", table_name="interview_sessions")
    op.drop_index("ix_interview_sessions_user_status_created", table_name="interview_sessions")
