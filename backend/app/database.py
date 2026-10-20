from datetime import datetime, timezone
from sqlalchemy import create_engine, String, Boolean, Integer, Text, Float
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker


def now():
    return datetime.now(timezone.utc).isoformat()


class Base(DeclarativeBase):
    pass


class Device(Base):
    __tablename__ = 'devices'
    mac: Mapped[str] = mapped_column(String(17), primary_key=True)
    ip: Mapped[str | None] = mapped_column(String(45))
    hostname: Mapped[str] = mapped_column(String(255), default='')
    alias: Mapped[str] = mapped_column(String(80), default='')
    trusted: Mapped[bool] = mapped_column(Boolean, default=False)
    protected: Mapped[bool] = mapped_column(Boolean, default=False)
    online: Mapped[bool] = mapped_column(Boolean, default=False)
    blocked: Mapped[bool] = mapped_column(Boolean, default=False)
    restriction_scope: Mapped[str] = mapped_column(String(255), default='')
    connection_type: Mapped[str] = mapped_column(String(20), default='unknown')
    port: Mapped[str] = mapped_column(String(16), default='')
    device_type: Mapped[str] = mapped_column(String(255), default='')
    first_seen: Mapped[str] = mapped_column(String(40), default=now)
    last_seen: Mapped[str | None] = mapped_column(String(40))
    misses: Mapped[int] = mapped_column(Integer, default=0)
    source: Mapped[str] = mapped_column(String(20), default='router')


class Event(Base):
    __tablename__ = 'events'
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    at: Mapped[str] = mapped_column(String(40), default=now, index=True)
    kind: Mapped[str] = mapped_column(String(50), index=True)
    mac: Mapped[str | None] = mapped_column(String(17), index=True)
    detail: Mapped[str] = mapped_column(Text, default='')
    notified: Mapped[bool] = mapped_column(Boolean, default=False)
    attempts: Mapped[int] = mapped_column(Integer, default=0)


class Schedule(Base):
    __tablename__ = 'schedules'
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    mac: Mapped[str] = mapped_column(String(17), index=True)
    action: Mapped[str] = mapped_column(String(10))
    timezone: Mapped[str] = mapped_column(String(80))
    local_time: Mapped[str] = mapped_column(String(5), default='')
    weekdays: Mapped[str] = mapped_column(String(20), default='')
    next_run: Mapped[str] = mapped_column(String(40), index=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    status: Mapped[str] = mapped_column(String(100), default='pending')


class Config(Base):
    __tablename__ = 'config'
    key: Mapped[str] = mapped_column(String(80), primary_key=True)
    value: Mapped[str] = mapped_column(Text)


def database(url):
    engine = create_engine(url, connect_args={'check_same_thread': False, 'timeout': 30})
    from sqlalchemy import event
    @event.listens_for(engine, 'connect')
    def pragmas(connection, _):
        cursor = connection.cursor()
        cursor.execute('PRAGMA journal_mode=WAL')
        cursor.execute('PRAGMA foreign_keys=ON')
        cursor.close()
    return engine, sessionmaker(engine, expire_on_commit=False)
