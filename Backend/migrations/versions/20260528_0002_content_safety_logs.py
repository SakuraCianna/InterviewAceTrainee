"""add content safety logs

Revision ID: 20260528_0002
Revises: 20260523_0001
Create Date: 2026-05-28
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260528_0002"
down_revision = "20260523_0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
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


def downgrade() -> None:
    op.drop_index("ix_content_safety_logs_risk_level", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_action", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_source", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_session_id", table_name="content_safety_logs")
    op.drop_index("ix_content_safety_logs_user_email", table_name="content_safety_logs")
    op.drop_table("content_safety_logs")
