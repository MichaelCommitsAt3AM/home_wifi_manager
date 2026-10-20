import asyncio
from datetime import datetime, timedelta, timezone
import httpx
import pytest
from sqlalchemy import select
from app.database import Schedule, Device
from app.connection import ConnectionMonitor, SpeedTests
from app.services import Controller
from app.adapters.base import CapabilityError, VerificationError
from test_system import system, MAC, HEADERS


def sample(router=True, internet=True, dns=True):
    return dict(router_reachable=router, internet_reachable=internet, dns_working=dns, tls_connect_ms=12 if internet else None)


def test_health_classification_debounce_history_and_monitoring_gap(system):
    client, c, _ = system
    monitor = c.connection
    at = datetime.now(timezone.utc)
    def record(data, seconds): return monitor.record(data, (at+timedelta(seconds=seconds)).isoformat())
    assert record(sample(), 0)['state'] == 'checking'
    assert record(sample(), 30)['state'] == 'healthy'
    assert record(sample(internet=False), 60)['state'] == 'healthy'
    outage = record(sample(internet=False), 90)
    assert outage['state'] == 'internet_down'
    assert len(outage['outages']) == 1 and outage['outages'][0]['started_at'] == (at+timedelta(seconds=60)).isoformat()
    record(sample(dns=False), 120)
    state = record(sample(dns=False), 150)
    assert state['state'] == 'dns_failure'
    assert state['outages'][1]['ended_at'] == (at+timedelta(seconds=120)).isoformat()
    restored = ConnectionMonitor(c.settings, c.sessions)
    assert restored.report()['state'] == 'dns_failure'
    state = record(sample(), 300)
    assert state['state'] == 'checking'
    assert state['outages'][0]['end_reason'] == 'monitoring_gap'
    assert state['outages'][0]['ended_at'] == (at+timedelta(seconds=150)).isoformat()
    assert record(sample(), 330)['state'] == 'healthy'
    assert client.get('/api/connection').status_code == 401
    assert client.get('/api/connection', headers=HEADERS).status_code == 200


async def test_timed_block_is_persisted_before_write_and_recovers_after_outage(system):
    client, c, a = system
    await c.sync()
    original = a.set_blocked
    async def check_saved(device, blocked):
        with c.sessions() as db:
            pending = db.scalar(select(Schedule).where(Schedule.enabled == True))
            assert pending.action == 'unblock' and pending.status == 'timed_pending'
        await original(device, blocked)
    a.set_blocked = check_saved
    await c.manage(MAC, True, timed_minutes=15)
    with c.sessions.begin() as db:
        timer = db.scalar(select(Schedule))
        assert timer.status == 'timed_active' and timer.enabled
        sid = timer.id
        timer.next_run = (datetime.now(timezone.utc)-timedelta(hours=2)).isoformat()
    assert client.delete(f'/api/schedules/{sid}', headers=HEADERS).status_code == 409
    a.set_blocked = original
    a.unavailable = True
    await c.run_schedules()
    with c.sessions.begin() as db:
        timer = db.get(Schedule, sid)
        assert timer.enabled and timer.status == 'timed_retry'
        timer.next_run = (datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat()
    restarted = Controller(c.settings, a, c.sessions)
    a.unavailable = False
    await restarted.run_schedules()
    with c.sessions() as db:
        assert not db.get(Schedule, sid).enabled
        assert not db.get(Device, MAC).blocked
    assert a.commands == [(MAC, True), (MAC, False)]
    await restarted.adguard.close()


async def test_timed_ambiguity_preserves_recovery_and_does_not_override_existing_blocks(system):
    _, c, a = system
    await c.sync()
    original = a.set_blocked
    async def ambiguous(device, blocked):
        await original(device, blocked)
        raise VerificationError('Simulated lost response')
    a.set_blocked = ambiguous
    with pytest.raises(VerificationError): await c.manage(MAC, True, timed_minutes=15)
    with c.sessions.begin() as db:
        timer = db.scalar(select(Schedule))
        assert timer.enabled and timer.status == 'timed_pending'
        timer.next_run = (datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat()
    with pytest.raises(CapabilityError): await c.manage(MAC, True, timed_minutes=15)
    a.set_blocked = original
    await c.run_schedules()
    assert not a.restrictions


async def test_release_keeps_due_timed_unblocks_for_resume(system):
    _, c, a = system
    await c.sync()
    await c.manage(MAC, True, timed_minutes=15)
    await c.release_router_access()
    with c.sessions.begin() as db:
        db.scalar(select(Schedule)).next_run = (datetime.now(timezone.utc)-timedelta(hours=1)).isoformat()
    await c.run_schedules()
    assert a.restrictions
    await c.resume_router_access()
    await c.run_schedules()
    assert not a.restrictions


def test_timed_validation_and_protection(system):
    client, c, a = system
    client.post('/api/sync', headers=HEADERS)
    for duration in [0, 1441, -1]:
        assert client.post(f'/api/devices/{MAC}/access', headers=HEADERS, json={'blocked':True,'confirmed':True,'minutes':duration}).status_code == 422
    assert client.post(f'/api/devices/{MAC}/access', headers=HEADERS, json={'blocked':False,'confirmed':True,'minutes':15}).status_code == 422
    client.patch(f'/api/devices/{MAC}', headers=HEADERS, json={'protected':True})
    assert client.post(f'/api/devices/{MAC}/access', headers=HEADERS, json={'blocked':True,'confirmed':True,'minutes':15}).status_code == 403
    with c.sessions() as db: assert not list(db.scalars(select(Schedule)))


async def test_speedtest_background_concurrency_persistence_and_failure(system):
    client, c, _ = system
    gate = asyncio.Event()
    async def runner():
        await gate.wait()
        return {'download_mbps':40.0, 'upload_mbps':12.0, 'latency_ms':5}
    speeds = SpeedTests(c.sessions, runner)
    assert (await speeds.start())['state'] == 'running'
    with pytest.raises(CapabilityError): await speeds.start()
    gate.set()
    await speeds.task
    assert speeds.report()['state'] == 'completed'
    assert SpeedTests(c.sessions).report()['download_mbps'] == 40
    with pytest.raises(CapabilityError): await speeds.start()
    assert client.post('/api/connection/speedtest', headers=HEADERS, json={'confirmed':False}).status_code == 422
    assert client.post('/api/connection/speedtest', json={'confirmed':True}).status_code == 401
    with c.sessions.begin() as db:
        from app.connection import save
        save(db,'speed_test',{'state':'running'})
    speeds.recover()
    assert speeds.report()['state'] == 'interrupted'
    async def fail(): raise httpx.ConnectError('Private diagnostics must not leak')
    speeds.runner = fail
    await speeds.start()
    await speeds.task
    assert speeds.report()['state'] == 'failed'
    assert 'Private' not in str(speeds.report())
