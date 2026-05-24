"""add system configs

Revision ID: 20260524_0003
Revises: 20260524_0002
Create Date: 2026-05-24
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260524_0003"
down_revision = "20260524_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "system_configs",
        sa.Column("key", sa.String(length=120), primary_key=True),
        sa.Column("value_json", postgresql.JSONB(), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("system_configs")
