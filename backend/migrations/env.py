from alembic import context
from sqlalchemy import create_engine
from app.database import Base
from app.core import Settings
from pathlib import Path

url = Settings().database_url
if url.startswith('sqlite:///'):
    Path(url.removeprefix('sqlite:///')).parent.mkdir(parents=True, exist_ok=True)
if context.is_offline_mode():
    context.configure(url=url, target_metadata=Base.metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()
else:
    with create_engine(url).connect() as connection:
        context.configure(connection=connection, target_metadata=Base.metadata)
        with context.begin_transaction():
            context.run_migrations()
