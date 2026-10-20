"""Initial schema."""
from alembic import op
import sqlalchemy as sa

revision = '0001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    op.create_table('devices',
        sa.Column('mac', sa.String(17), primary_key=True),
        sa.Column('ip', sa.String(45), nullable=True),
        *[sa.Column(k, sa.String(n), nullable=False) for k, n in [('hostname',255),('alias',80),('restriction_scope',255),('connection_type',20),('port',16),('device_type',255),('first_seen',40),('source',20)]],
        sa.Column('last_seen', sa.String(40), nullable=True),
        *[sa.Column(k, sa.Boolean(), nullable=False) for k in ['trusted','protected','online','blocked']],
        sa.Column('misses', sa.Integer(), nullable=False))
    op.create_table('events', sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('at', sa.String(40), nullable=False), sa.Column('kind', sa.String(50), nullable=False),
        sa.Column('mac', sa.String(17), nullable=True), sa.Column('detail', sa.Text(), nullable=False),
        sa.Column('notified', sa.Boolean(), nullable=False), sa.Column('attempts', sa.Integer(), nullable=False))
    for k in ['at','kind','mac']:
        op.create_index('ix_events_' + k, 'events', [k])
    op.create_table('schedules', sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('mac', sa.String(17), nullable=False), sa.Column('action', sa.String(10), nullable=False),
        sa.Column('timezone', sa.String(80), nullable=False), sa.Column('local_time', sa.String(5), nullable=False),
        sa.Column('weekdays', sa.String(20), nullable=False), sa.Column('next_run', sa.String(40), nullable=False),
        sa.Column('enabled', sa.Boolean(), nullable=False), sa.Column('status', sa.String(100), nullable=False))
    for k in ['mac','next_run']:
        op.create_index('ix_schedules_' + k, 'schedules', [k])
    op.create_table('config', sa.Column('key', sa.String(80), primary_key=True), sa.Column('value', sa.Text(), nullable=False))


def downgrade():
    for table in ['config', 'schedules', 'events', 'devices']:
        op.drop_table(table)
