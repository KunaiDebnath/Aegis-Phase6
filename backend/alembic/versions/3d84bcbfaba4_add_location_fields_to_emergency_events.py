"""add_location_fields_to_emergency_events

Revision ID: 3d84bcbfaba4
Revises: 
Create Date: 2026-10-26 00:20:45.942389

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '3d84bcbfaba4'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema: Add nullable location columns to emergency_events."""
    op.add_column(
        'emergency_events',
        sa.Column('latitude', sa.Float(), nullable=True),
    )
    op.add_column(
        'emergency_events',
        sa.Column('longitude', sa.Float(), nullable=True),
    )
    op.add_column(
        'emergency_events',
        sa.Column('location_accuracy_meters', sa.Float(), nullable=True),
    )
    op.add_column(
        'emergency_events',
        sa.Column('location_timestamp', sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    """Downgrade schema: Remove location columns from emergency_events."""
    op.drop_column('emergency_events', 'location_timestamp')
    op.drop_column('emergency_events', 'location_accuracy_meters')
    op.drop_column('emergency_events', 'longitude')
    op.drop_column('emergency_events', 'latitude')
