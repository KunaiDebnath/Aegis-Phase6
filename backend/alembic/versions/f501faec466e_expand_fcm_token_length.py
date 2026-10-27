"""expand_fcm_token_length

Revision ID: f501faec466e
Revises: 9f5e54e80073
Create Date: 2026-10-27 11:09:27.248642

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f501faec466e'
down_revision: Union[str, Sequence[str], None] = '9f5e54e80073'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.alter_column(
        'device_registrations',
        'fcm_token',
        existing_type=sa.String(length=255),
        type_=sa.String(length=1024),
        existing_nullable=True,
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.alter_column(
        'device_registrations',
        'fcm_token',
        existing_type=sa.String(length=1024),
        type_=sa.String(length=255),
        existing_nullable=True,
    )
