"""add interview capability vector store

Revision ID: 20260606_0003
Revises: 20260528_0002
Create Date: 2026-06-06
"""

from alembic import op


revision = "20260606_0003"
down_revision = "20260528_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute(
        """
        CREATE TABLE interview_capability_vectors (
            id VARCHAR(120) PRIMARY KEY,
            card_id VARCHAR(120) NOT NULL UNIQUE,
            title VARCHAR(160) NOT NULL,
            interview_types JSONB NOT NULL,
            content_hash VARCHAR(64) NOT NULL,
            embedding_model VARCHAR(80) NOT NULL,
            embedding_dimensions INTEGER NOT NULL,
            source_text TEXT NOT NULL,
            metadata_json JSONB NOT NULL,
            embedding vector(384) NOT NULL,
            updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
        )
        """
    )
    op.execute(
        """
        CREATE INDEX ix_interview_capability_vectors_interview_types
        ON interview_capability_vectors
        USING gin (interview_types)
        """
    )
    op.execute(
        """
        CREATE INDEX ix_interview_capability_vectors_embedding_hnsw
        ON interview_capability_vectors
        USING hnsw (embedding vector_cosine_ops)
        """
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_interview_capability_vectors_embedding_hnsw")
    op.execute("DROP INDEX IF EXISTS ix_interview_capability_vectors_interview_types")
    op.execute("DROP TABLE IF EXISTS interview_capability_vectors")
