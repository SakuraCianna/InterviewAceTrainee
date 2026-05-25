"""seed default admin account

Revision ID: 20260525_0002
Revises: 20260523_0001
Create Date: 2026-05-25
"""

from alembic import op

revision = "20260525_0002"
down_revision = "20260523_0001"
branch_labels = None
depends_on = None

DEFAULT_ADMIN_ID = "75451592-2000-4000-8000-000000000001"
DEFAULT_ADMIN_EMAIL = "754515922@qq.com"
DEFAULT_ADMIN_PASSWORD_HASH = "$argon2id$v=19$m=65536,t=3,p=4$nTC5ihZxqGcWiJ3pa0D+nA$qk6WeU0Pi5I+4Cb0/w7VdbcL1dGEQqHIA1dHIw4QCuY"


def upgrade() -> None:
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


def downgrade() -> None:
    op.execute(f"DELETE FROM users WHERE email = '{DEFAULT_ADMIN_EMAIL}' AND id = '{DEFAULT_ADMIN_ID}'")
