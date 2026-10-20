from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
import asyncio
import hmac
import time
from collections import defaultdict, deque
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI, Depends, HTTPException, Request, Query
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select
from .core import Settings, configure_logging
from .database import database, Device, Event, Schedule, Config, now
from .services import Controller, recurring_next
from .adapters.huawei_hg8546m import HuaweiHG8546M, normalize_mac
from .adapters.base import RouterError, CapabilityError


def serialize(obj):
    return {c.name: getattr(obj, c.name) for c in obj.__table__.columns}


class DevicePatch(BaseModel):
    alias: str | None = Field(default=None, max_length=80)
    trusted: bool | None = None
    protected: bool | None = None


class AccessChange(BaseModel):
    blocked: bool
    confirmed: bool
    minutes: int | None = Field(default=None, ge=1, le=1440)


class RouterRelease(BaseModel):
    confirmed: bool


class ProtectionChange(BaseModel):
    enabled: bool
    minutes: int = Field(default=5, ge=1, le=1440)
    confirmed: bool


class DomainException(BaseModel):
    domain: str = Field(min_length=3, max_length=253)
    allowed: bool
    confirmed: bool


class Preferences(BaseModel):
    poll_seconds: int = Field(ge=30, le=3600)
    notification_categories: list[str] = Field(default_factory=lambda: ['unknown_device', 'router_offline', 'router_restored', 'schedule_executed', 'schedule_failed', 'management_failed'], max_length=20)

    @field_validator('notification_categories')
    @classmethod
    def categories(cls, values):
        allowed = {'unknown_device', 'device_discovered', 'device_connected', 'device_disconnected', 'router_offline', 'router_restored', 'schedule_executed', 'schedule_failed', 'management_failed', 'device_blocked', 'device_unblocked'}
        if not set(values) <= allowed:
            raise ValueError('Unknown notification category')
        return values


class ScheduleInput(BaseModel):
    mac: str
    action: str = Field(pattern='^(block|unblock)$')
    timezone: str = 'Africa/Nairobi'
    run_at: datetime | None = None
    local_time: str = Field(default='', pattern=r'^(?:[0-2][0-9]:[0-5][0-9])?$')
    weekdays: list[int] = Field(default_factory=list, max_length=7)


def create_app(settings=None, adapter=None, start_scheduler=True):
    settings = settings or Settings()
    if settings.database_url.startswith('sqlite:///'):
        Path(settings.database_url.removeprefix('sqlite:///')).parent.mkdir(parents=True, exist_ok=True)
    engine, sessions = database(settings.database_url)
    controller = Controller(settings, adapter or HuaweiHG8546M(settings), sessions)
    configure_logging()

    @asynccontextmanager
    async def lifespan(app):
        controller.speed_tests.recover()
        scheduler = AsyncIOScheduler(timezone='UTC')
        if start_scheduler:
            scheduler.add_job(controller.tick, 'interval', seconds=5, max_instances=1, coalesce=True, next_run_time=datetime.now(timezone.utc))
            scheduler.start()
        yield
        if scheduler.running:
            scheduler.shutdown(wait=False)
        async with controller.lock:
            await controller.adapter.close()
        await controller.speed_tests.close()
        await controller.adguard.close()
        engine.dispose()

    app = FastAPI(title='Home WiFi Manager', version='1.2.0', lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.controller, app.state.sessions = controller, sessions
    buckets = defaultdict(deque)
    security = HTTPBearer(auto_error=False)

    @app.middleware('http')
    async def rate_limit(request: Request, call_next):
        if request.url.path == '/health':
            return await call_next(request)
        # Do not key on user-supplied forwarded headers.
        client = request.client.host if request.client else 'unknown'
        key = (client, request.method != 'GET')
        queue, current = buckets[key], time.monotonic()
        while queue and queue[0] < current - 60:
            queue.popleft()
        if len(queue) >= (20 if key[1] else 180):
            return JSONResponse({'detail': 'Rate limit exceeded'}, 429, headers={'Retry-After': '60'})
        queue.append(current)
        # Bound source-cardinality memory (only loopback is exposed by default).
        if len(buckets) > 4096:
            for oldkey in list(buckets)[:1024]:
                if not buckets[oldkey] or buckets[oldkey][-1] < current - 60:
                    del buckets[oldkey]
        response = await call_next(request)
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        return response

    async def auth(credentials: HTTPAuthorizationCredentials | None = Depends(security)):
        if not credentials or not hmac.compare_digest(credentials.credentials.encode(), settings.api_token.get_secret_value().encode()):
            raise HTTPException(401, 'Invalid API token', headers={'WWW-Authenticate': 'Bearer'})

    @app.exception_handler(RouterError)
    async def router_error(_, error):
        return JSONResponse({'detail': str(error), 'code': error.code}, 409 if isinstance(error, CapabilityError) else 502)

    @app.exception_handler(PermissionError)
    async def protected_error(_, error):
        return JSONResponse({'detail': str(error)}, 403)

    @app.exception_handler(ValueError)
    async def value_error(_, error):
        return JSONResponse({'detail': str(error)}, 422)

    def mac(value):
        try:
            return normalize_mac(value)
        except ValueError:
            raise HTTPException(422, 'Invalid unicast MAC address')

    @app.get('/health')
    async def health():
        # Liveness only, no network identities or private monitoring data.
        with sessions() as db:
            db.execute(select(1)).scalar_one()
        return {'status': 'ok', 'version': '1.2.0'}

    @app.get('/api/dashboard', dependencies=[Depends(auth)])
    async def dashboard():
        access = controller.router_access_status()
        with sessions() as db:
            devices = list(db.scalars(select(Device)))
            router = dict(controller.health['router'])
            if access['router_access_paused']:
                router.update(block_supported=False, scheduling_supported=False)
            return {**controller.health, **access, 'router': router, 'connection': controller.connection.report(),
                    'router_online': None if access['router_access_paused'] else controller.health['router_online'],
                    'total': len(devices),
                    'connected': sum(d.online for d in devices),
                    'unknown': sum(d.online and not d.trusted for d in devices),
                    'blocked': sum(d.blocked for d in devices),
                    'monitoring': start_scheduler and not access['router_access_paused'],
                    'stale': access['router_access_paused'] or controller.health['router_online'] is not True,
                    'events': [serialize(e) for e in db.scalars(select(Event).order_by(Event.id.desc()).limit(10))]}

    @app.get('/api/router/access', dependencies=[Depends(auth)])
    async def router_access():
        return controller.router_access_status()

    @app.post('/api/router/access/release', dependencies=[Depends(auth)])
    async def release_router_access(body: RouterRelease):
        if not body.confirmed:
            raise HTTPException(422, 'Explicit confirmation is required')
        return await controller.release_router_access()

    @app.post('/api/router/access/resume', dependencies=[Depends(auth)])
    async def resume_router_access():
        return await controller.resume_router_access()

    @app.get('/api/devices', dependencies=[Depends(auth)])
    async def devices():
        with sessions() as db:
            return [serialize(d) for d in db.scalars(select(Device).order_by(Device.online.desc(), Device.hostname))]

    @app.get('/api/adguard', dependencies=[Depends(auth)])
    async def adguard():
        result = await controller.adguard.status()
        controller.health['adguard'] = result
        return result

    @app.get('/api/connection', dependencies=[Depends(auth)])
    async def connection():
        return {**controller.connection.report(), 'speed_test': controller.speed_tests.report()}

    @app.post('/api/connection/check', dependencies=[Depends(auth)])
    async def check_connection():
        await controller.connection.check(force=True)
        return await connection()

    @app.post('/api/connection/speedtest', dependencies=[Depends(auth)])
    async def speedtest(body: RouterRelease):
        if not body.confirmed:
            raise HTTPException(422, 'Confirm the server speed test and its data usage')
        return await controller.speed_tests.start()

    @app.post('/api/adguard/protection', dependencies=[Depends(auth)])
    async def protection(body: ProtectionChange):
        if not body.confirmed: raise HTTPException(422, 'Confirmation required')
        await controller.adguard.protection(body.enabled, body.minutes)
        with sessions.begin() as db: controller.event(db, 'adguard_protection_changed')
        return await adguard()

    @app.post('/api/adguard/filters/refresh', dependencies=[Depends(auth)])
    async def refresh_filters(body: RouterRelease):
        if not body.confirmed: raise HTTPException(422, 'Confirmation required')
        result = await controller.adguard.refresh_filters()
        with sessions.begin() as db: controller.event(db, 'adguard_filters_refreshed')
        return result

    @app.get('/api/adguard/exceptions', dependencies=[Depends(auth)])
    async def domain_exceptions():
        return await controller.adguard.exceptions()

    @app.post('/api/adguard/exceptions', dependencies=[Depends(auth)])
    async def domain_exception(body: DomainException):
        if not body.confirmed: raise HTTPException(422, 'Confirmation required')
        result = await controller.adguard.exception(body.domain, body.allowed)
        with sessions.begin() as db: controller.event(db, 'adguard_exception_changed')
        return result

    @app.get('/api/devices/{address}', dependencies=[Depends(auth)])
    async def detail(address: str):
        with sessions() as db:
            device = db.get(Device, mac(address))
            if not device:
                raise HTTPException(404, 'Device not found')
            return serialize(device)

    @app.patch('/api/devices/{address}', dependencies=[Depends(auth)])
    async def update_device(address: str, patch: DevicePatch):
        async with controller.lock:
            with sessions.begin() as db:
                device = db.get(Device, mac(address))
                if not device:
                    raise HTTPException(404, 'Device not found')
                if patch.protected is False and controller.protected(device):
                    raise HTTPException(403, 'Infrastructure protection is configured on the server')
                for key, value in patch.model_dump(exclude_none=True).items():
                    setattr(device, key, value)
                controller.event(db, 'device_metadata_changed', device.mac)
                return serialize(device)

    @app.post('/api/devices/{address}/access', dependencies=[Depends(auth)])
    async def access(address: str, change: AccessChange):
        if not change.confirmed:
            raise HTTPException(422, 'Explicit confirmation is required')
        if change.minutes is not None and not change.blocked:
            raise HTTPException(422, 'Duration applies only to a new block')
        await controller.manage(mac(address), change.blocked, timed_minutes=change.minutes)
        return await detail(address)

    @app.post('/api/sync', dependencies=[Depends(auth)])
    async def sync():
        if controller.router_access_status()['router_access_paused']:
            return await dashboard()
        if not await controller.sync():
            raise HTTPException(502, controller.health['error'])
        return await dashboard()

    @app.get('/api/activity', dependencies=[Depends(auth)])
    async def activity(address: str | None = None, kind: str | None = None,
                       before: int | None = None, after: int | None = None, limit: int = Query(100, ge=1, le=500)):
        query = select(Event)
        if address:
            query = query.where(Event.mac == mac(address))
        if kind:
            query = query.where(Event.kind == kind)
        if before:
            query = query.where(Event.id < before)
        if after is not None:
            query = query.where(Event.id > after)
        with sessions() as db:
            return [serialize(e) for e in db.scalars(query.order_by(Event.id.asc() if after is not None else Event.id.desc()).limit(limit))]

    @app.get('/api/settings', dependencies=[Depends(auth)])
    async def preferences():
        access = controller.router_access_status()
        with sessions() as db:
            return {**access, 'poll_seconds': int(controller.config(db, 'poll_seconds', str(settings.poll_seconds))),
                    'notification_categories': controller.config(db, 'notification_categories', 'unknown_device,router_offline,router_restored,schedule_executed,schedule_failed,management_failed').split(','),
                    'writes_enabled': settings.writes_enabled and settings.verified_writes and not access['router_access_paused'],
                    'retention_days': settings.retention_days, 'ntfy_configured': bool(settings.ntfy_url),
                    'router_url': settings.router_url, 'version': '1.2.0'}

    @app.put('/api/settings', dependencies=[Depends(auth)])
    async def save_preferences(body: Preferences):
        with sessions.begin() as db:
            for key, value in [('poll_seconds', str(body.poll_seconds)), ('notification_categories', ','.join(body.notification_categories))]:
                db.merge(Config(key=key, value=value))
            controller.event(db, 'settings_changed')
        return await preferences()

    @app.get('/api/schedules', dependencies=[Depends(auth)])
    async def schedules(address: str | None = None):
        with sessions() as db:
            query = select(Schedule).order_by(Schedule.next_run)
            if address:
                query = query.where(Schedule.mac == mac(address))
            return [serialize(s) for s in db.scalars(query)]

    @app.post('/api/schedules', dependencies=[Depends(auth)])
    async def add_schedule(body: ScheduleInput):
        if not (settings.writes_enabled and settings.verified_writes):
            raise CapabilityError('Scheduling disabled until router writes are verified')
        address = mac(body.mac)
        try:
            ZoneInfo(body.timezone)
        except ZoneInfoNotFoundError:
            raise HTTPException(422, 'Invalid timezone')
        if bool(body.run_at) == bool(body.local_time):
            raise HTTPException(422, 'Choose either one-time or recurring execution')
        weekdays = ','.join(map(str, sorted(set(body.weekdays))))
        if body.local_time:
            if not body.weekdays or any(x < 0 or x > 6 for x in body.weekdays) or int(body.local_time[:2]) > 23:
                raise HTTPException(422, 'Invalid recurring time or weekdays')
            next_run = recurring_next(body.local_time, weekdays, body.timezone)
        else:
            if not body.run_at.tzinfo or body.run_at <= datetime.now(timezone.utc):
                raise HTTPException(422, 'An aware future timestamp is required')
            next_run = body.run_at.astimezone(timezone.utc).isoformat()
        async with controller.lock:
            with sessions.begin() as db:
                controller.require_router_access()
                device = db.get(Device, address)
                if not device:
                    raise HTTPException(404, 'Device not found')
                if device.protected or controller.protected(device):
                    raise HTTPException(403, 'Critical devices cannot be scheduled')
                active = list(db.scalars(select(Schedule).where(Schedule.mac == address, Schedule.enabled == True)))
                for item in active:
                    if item.status.startswith('timed_'):
                        raise HTTPException(409, 'Unblock the device before adding another schedule')
                    same_recurring = body.local_time and item.local_time == body.local_time and item.timezone == body.timezone and set(item.weekdays.split(',')) & set(weekdays.split(','))
                    if item.next_run == next_run or same_recurring:
                        raise HTTPException(409, 'Conflicting schedule')
                item = Schedule(mac=address, action=body.action, timezone=body.timezone,
                                next_run=next_run, local_time=body.local_time, weekdays=weekdays)
                db.add(item)
                db.flush()
                controller.event(db, 'schedule_created', address, f'schedule:{item.id}')
                return serialize(item)

    @app.delete('/api/schedules/{sid}', dependencies=[Depends(auth)])
    async def disable_schedule(sid: int):
        async with controller.lock:
            with sessions.begin() as db:
                item = db.get(Schedule, sid)
                if not item:
                    raise HTTPException(404, 'Schedule not found')
                if item.enabled and item.status.startswith('timed_'):
                    raise HTTPException(409, 'Unblock the device to end its timed block')
                item.enabled = False
                item.status = 'cancelled'
                controller.event(db, 'schedule_cancelled', item.mac, f'schedule:{sid}')
        return {'cancelled': True}

    return app
