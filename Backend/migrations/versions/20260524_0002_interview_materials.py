"""add interview materials

Revision ID: 20260524_0002
Revises: 20260523_0001
Create Date: 2026-05-24
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260524_0002"
down_revision = "20260523_0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
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

    op.add_column("interview_sessions", sa.Column("material_id", postgresql.UUID(as_uuid=False), nullable=True))
    op.create_index("ix_interview_sessions_material_id", "interview_sessions", ["material_id"])


def downgrade() -> None:
    op.drop_index("ix_interview_sessions_material_id", table_name="interview_sessions")
    op.drop_column("interview_sessions", "material_id")
    op.drop_index("ix_interview_materials_interview_type", table_name="interview_materials")
    op.drop_index("ix_interview_materials_user_email", table_name="interview_materials")
    op.drop_table("interview_materials")
