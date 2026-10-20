import asyncio
import logging
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import httpx
from sqlalchemy import select, delete
from .database import Device, Event, Schedule, Config, now
from .adapters.base import Observation, RouterError, CapabilityError
from .adguard import AdGuard
from .connection import ConnectionMonitor, SpeedTests

log = logging.getLogger(__name__)


def recurring_next(local_time, weekdays, zone, after=None):
    after = after or datetime.now(timezone.utc)
    tz = ZoneInfo(zone)
    local = after.astimezone(tz)
    hour, minute = map(int, local_time.split(':'))
    days = {int(x) for x in weekdays.split(',')}
    for offset in range(9):
        candidate = (local + timedelta(days=offset)).replace(hour=hour, minute=minute, second=0, microsecond=0, fold=0)
        utc = candidate.astimezone(timezone.utc)
        # Skip nonexistent DST times, execute repeated times only once (fold=0).
        if candidate.weekday() in days and utc > after and utc.astimezone(tz).replace(fold=0) == candidate:
            return utc.isoformat()
    raise ValueError('No next occurrence')


class Controller:
    def __init__(self, settings, adapter, sessions):
        self.settings, self.adapter, self.sessions = settings, adapter, sessions
        self.adguard = AdGuard(settings)
        self.connection = ConnectionMonitor(settings, sessions)
        self.speed_tests = SpeedTests(sessions)
        self.lock = asyncio.Lock()
        self.health = {'router_online': None, 'last_sync': None, 'last_attempt': None,
                       'failures': 0, 'error': None, 'router': {}}
        self.next_poll = 0.0

    def event(self, db, kind, mac=None, detail=''):
        event = Event(kind=kind, mac=mac, detail=detail)
        db.add(event)
        return event

    def protected(self, observation):
        return (observation.mac in self.settings.protected_macs.lower().split(',') or
                observation.ip in self.settings.protected_ips.split(','))

    def config(self, db, key, fallback):
        entry = db.get(Config, key)
        return entry.value if entry else fallback

    def router_access_status(self):
        with self.sessions() as db:
            return {'router_access_paused': self.config(db, 'router_access_paused', 'false') == 'true',
                    'router_logout_verified': self.config(db, 'router_logout_verified', 'false') == 'true',
                    'router_access_paused_at': self.config(db, 'router_access_paused_at', '') or None,
                    'router_access_error': self.config(db, 'router_access_error', '') or None}

    def require_router_access(self):
        if self.router_access_status()['router_access_paused']:
            raise CapabilityError('Router access is released. Resume it in Settings before managing devices.')

    async def release_router_access(self):
        async with self.lock:
            previous = self.router_access_status()
            if previous['router_access_paused'] and previous['router_logout_verified']:
                return previous
            # Commit the pause first: even a failed logout or restart must not log back in.
            with self.sessions.begin() as db:
                db.merge(Config(key='router_access_paused', value='true'))
                if not previous['router_access_paused']:
                    db.merge(Config(key='router_access_paused_at', value=now()))
                db.merge(Config(key='router_logout_verified', value='false'))
                db.merge(Config(key='router_access_error', value=''))
            verified, error = False, ''
            try:
                verified = await self.adapter.logout()
                if not verified:
                    error = 'logout_not_verified'
            except RouterError as exception:
                error = exception.code
            with self.sessions.begin() as db:
                db.merge(Config(key='router_logout_verified', value='true' if verified else 'false'))
                db.merge(Config(key='router_access_error', value=error))
                self.event(db, 'router_access_released' if verified else 'router_access_pause_unconfirmed', detail=error)
            self.health.update(router_online=None, error=None)
            return self.router_access_status()

    async def resume_router_access(self):
        async with self.lock:
            if not self.router_access_status()['router_access_paused']:
                return self.router_access_status()
            # Failed explicit reauthentication leaves the durable pause intact.
            await self.adapter.resume()
            with self.sessions.begin() as db:
                for schedule in db.scalars(select(Schedule).where(Schedule.enabled == True, Schedule.next_run <= now(), ~Schedule.status.like('timed_%'))):
                    schedule.status = 'skipped_while_access_released'
                    if schedule.local_time:
                        schedule.next_run = recurring_next(schedule.local_time, schedule.weekdays, schedule.timezone)
                    else:
                        schedule.enabled = False
                    self.event(db, 'schedule_skipped', schedule.mac, f'schedule:{schedule.id}; router access released')
                for key in ('router_access_paused', 'router_logout_verified'):
                    db.merge(Config(key=key, value='false'))
                db.merge(Config(key='router_access_error', value=''))
                self.event(db, 'router_access_resumed')
            self.health.update(router_online=None, failures=0, error=None)
            self.next_poll = 0.0
        await self.sync()
        return self.router_access_status()

    async def sync(self):
        async with self.lock:
            if self.router_access_status()['router_access_paused']:
                return False
            self.health['last_attempt'] = now()
            try:
                observations, restrictions, status = await self.adapter.snapshot()
                with self.sessions.begin() as db:
                    if self.health['router_online'] is False:
                        self.event(db, 'router_restored')
                    by_mac = {d.mac: d for d in observations}
                    existing = {d.mac: d for d in db.scalars(select(Device))}
                    for observation in observations:
                        device = existing.get(observation.mac)
                        fresh = device is None
                        if fresh:
                            device = Device(mac=observation.mac, online=False, trusted=False, misses=0)
                            db.add(device)
                            existing[device.mac] = device
                            self.event(db, 'device_discovered', device.mac)
                        device.ip, device.hostname = observation.ip, observation.hostname
                        device.connection_type, device.port = observation.connection_type, observation.port
                        device.device_type = observation.device_type
                        if self.protected(observation):
                            device.protected = True
                            device.trusted = True
                        if observation.online:
                            if not device.online:
                                self.event(db, 'device_connected', device.mac)
                                if not device.trusted:
                                    self.event(db, 'unknown_device', device.mac)
                            device.online, device.misses, device.last_seen = True, 0, now()
                        else:
                            device.misses += 1
                            if device.online and device.misses >= self.settings.offline_polls:
                                device.online = False
                                self.event(db, 'device_disconnected', device.mac)
                    for mac, device in existing.items():
                        if mac not in by_mac:
                            device.misses += 1
                            if device.online and device.misses >= self.settings.offline_polls:
                                device.online = False
                                self.event(db, 'device_disconnected', mac)
                    for restriction in restrictions:
                        if restriction.mac not in existing:
                            device = Device(mac=restriction.mac, online=False, trusted=False,
                                            source='router_filter', connection_type=restriction.scope,
                                            port=restriction.ssid.replace('SSID-', 'SSID'))
                            db.add(device)
                            existing[device.mac] = device
                    for device in existing.values():
                        matches = [r for r in restrictions if r.mac == device.mac]
                        device.blocked = bool(matches)
                        device.restriction_scope = ', '.join(r.scope + (':' + r.ssid if r.ssid else '') for r in matches)
                    cutoff = (datetime.now(timezone.utc) - timedelta(days=self.settings.retention_days)).isoformat()
                    db.execute(delete(Event).where(Event.at < cutoff))
                    db.execute(delete(Schedule).where(Schedule.enabled == False, Schedule.next_run < cutoff))
                self.health.update(router_online=True, last_sync=now(), failures=0, error=None, router=status)
                log.info('router_sync_complete')
                return True
            except RouterError as e:
                if self.health['router_online'] is not False:
                    with self.sessions.begin() as db:
                        self.event(db, 'router_offline', detail=e.code)
                self.health.update(router_online=False, failures=self.health['failures'] + 1, error=e.code)
                # Preserve device states on failed snapshots; report that they are stale.
                log.warning(e.code)
                return False

    async def manage(self, mac, blocked, scheduled=False, schedule_id=None, timed_minutes=None):
        async with self.lock:
            self.require_router_access()
            if not (self.settings.writes_enabled and self.settings.verified_writes):
                raise CapabilityError('Router writes are disabled')
            with self.sessions() as db:
                if schedule_id is not None:
                    schedule = db.get(Schedule, schedule_id)
                    if not schedule or not schedule.enabled:
                        raise CapabilityError('Schedule was cancelled or paused')
                device = db.get(Device, mac)
                if not device:
                    raise ValueError('Device not found')
                if blocked and (device.protected or self.protected(device)):
                    with self.sessions.begin() as audit:
                        self.event(audit, 'management_failed', mac, 'protected_device')
                    raise PermissionError('This critical device is protected')
                observation = Observation(device.mac, device.ip, device.hostname, device.online,
                                          device.connection_type, device.port)
            try:
                # Refresh association immediately before sending a command, avoiding stale IP/SSID identity.
                observations, restrictions, _ = await self.adapter.snapshot()
                current = next((d for d in observations if d.mac == mac), observation)
                if blocked and self.protected(current):
                    raise PermissionError('This critical device is protected')
                timer_id = None
                if timed_minutes is not None:
                    if not blocked or any(r.mac == mac for r in restrictions):
                        raise CapabilityError('Timed blocking requires an unrestricted device; refresh its state first')
                    # Persist the recovery action BEFORE a potentially ambiguous router write.
                    with self.sessions.begin() as db:
                        for item in db.scalars(select(Schedule).where(Schedule.mac == mac, Schedule.enabled == True)):
                            item.enabled, item.status = False, 'paused_by_manual_action'
                        timer = Schedule(mac=mac, action='unblock', timezone='UTC',
                            next_run=(datetime.now(timezone.utc)+timedelta(minutes=timed_minutes)).isoformat(), status='timed_pending')
                        db.add(timer)
                        db.flush()
                        timer_id = timer.id
                        self.event(db, 'timed_block_requested', mac, f'schedule:{timer.id}; minutes:{timed_minutes}')
                await self.adapter.set_blocked(current, blocked)
                with self.sessions.begin() as db:
                    device = db.get(Device, mac)
                    device.blocked = blocked
                    self.event(db, 'device_blocked' if blocked else 'device_unblocked', mac,
                               'schedule' if scheduled else 'manual; active schedules paused')
                    if timer_id is not None:
                        db.get(Schedule, timer_id).status = 'timed_active'
                    if not scheduled:
                        for schedule in db.scalars(select(Schedule).where(Schedule.mac == mac, Schedule.enabled == True)):
                            if schedule.id != timer_id:
                                schedule.enabled = False
                                schedule.status = 'paused_by_manual_action'
            except (RouterError, PermissionError) as e:
                with self.sessions.begin() as db:
                    self.event(db, 'management_failed', mac, getattr(e, 'code', 'protected_device'))
                raise
        await self.sync()

    async def run_schedules(self):
        if self.router_access_status()['router_access_paused'] or not (self.settings.writes_enabled and self.settings.verified_writes):
            return
        with self.sessions() as db:
            ids = list(db.scalars(select(Schedule.id).where(Schedule.enabled == True, Schedule.next_run <= now()).order_by(Schedule.next_run, Schedule.id)))
        for sid in ids:
            with self.sessions() as db:
                item = db.get(Schedule, sid)
                if not item or not item.enabled:
                    continue
                mac, action = item.mac, item.action
                timed = item.status.startswith('timed_')
                missed = not timed and datetime.now(timezone.utc) - datetime.fromisoformat(item.next_run) > timedelta(minutes=5)
            success = False
            try:
                if missed:
                    raise CapabilityError('Stale occurrence skipped after outage')
                await self.manage(mac, action == 'block', scheduled=True, schedule_id=sid)
                success = True
            except (RouterError, PermissionError, ValueError):
                if self.router_access_status()['router_access_paused']:
                    return
                pass
            with self.sessions.begin() as db:
                item = db.get(Schedule, sid)
                if not item.enabled:
                    continue
                self.event(db, 'schedule_executed' if success else 'schedule_failed', mac, f'schedule:{sid}')
                if timed and not success:
                    item.status = 'timed_retry'
                    item.next_run = (datetime.now(timezone.utc)+timedelta(seconds=60)).isoformat()
                    continue
                item.status = 'completed' if success else ('missed_after_outage' if missed else 'failed')
                if item.local_time:
                    item.next_run = recurring_next(item.local_time, item.weekdays, item.timezone)
                else:
                    item.enabled = False

    async def notify(self):
        if not self.settings.ntfy_url:
            return
        with self.sessions() as db:
            enabled = self.config(db, 'notification_categories', 'unknown_device,router_offline,router_restored,schedule_executed,schedule_failed,management_failed').split(',')
            ids = list(db.scalars(select(Event.id).where(Event.notified == False, Event.attempts < 5, Event.kind.in_(enabled)).order_by(Event.id).limit(5)))
        async with httpx.AsyncClient(timeout=10, follow_redirects=False, trust_env=False) as client:
            for eid in ids:
                with self.sessions() as db:
                    event = db.get(Event, eid)
                    kind, at = event.kind, event.at
                try:
                    # No MAC/IP/hostname is sent to notification infrastructure.
                    headers = {'Title': 'Home network', 'Tags': 'house'}
                    if self.settings.ntfy_token.get_secret_value():
                        headers['Authorization'] = 'Bearer ' + self.settings.ntfy_token.get_secret_value()
                    response = await client.post(self.settings.ntfy_url, content=f'{kind.replace("_", " ")} at {at}', headers=headers)
                    response.raise_for_status()
                    delivered = True
                except httpx.HTTPError:
                    delivered = False
                with self.sessions.begin() as db:
                    event = db.get(Event, eid)
                    event.attempts += 1
                    event.notified = delivered

    async def tick(self):
        await self.connection.check()
        self.health['adguard'] = await self.adguard.status()
        loop = asyncio.get_running_loop()
        if not self.router_access_status()['router_access_paused'] and loop.time() >= self.next_poll:
            await self.sync()
            with self.sessions() as db:
                interval = int(self.config(db, 'poll_seconds', str(self.settings.poll_seconds)))
            self.next_poll = loop.time() + min(900, interval * 2 ** min(self.health['failures'], 4))
        await self.run_schedules()
        await self.notify()
