"""add target school to interview materials

Revision ID: 20260525_0003
Revises: 20260525_0002
Create Date: 2026-05-25
"""

from alembic import op
import sqlalchemy as sa

revision = "20260525_0003"
down_revision = "20260525_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("interview_materials", sa.Column("target_school", sa.String(length=160), nullable=True))


def downgrade() -> None:
    op.drop_column("interview_materials", "target_school")
