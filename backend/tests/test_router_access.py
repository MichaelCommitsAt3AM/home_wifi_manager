from datetime import datetime, timedelta, timezone
import httpx
import pytest
from sqlalchemy import select
from app.database import Event, Schedule
from app.services import Controller
from app.adapters.base import UnavailableError, VerificationError
from app.adapters.huawei_hg8546m import HuaweiHG8546M, DEVICE_PATH
from test_system import system, settings, HEADERS, MAC, FIXTURE


async def test_pause_is_durable_and_blocks_all_router_work(system):
    client, controller, adapter = system
    await controller.sync()
    last_sync = controller.health['last_sync']
    calls = []
    async def logout():
        calls.append('logout')
        return True
    async def forbidden():
        raise AssertionError('Paused controller contacted the router')
    adapter.logout = logout
    assert client.post('/api/router/access/release', json={'confirmed': False}, headers=HEADERS).status_code == 422
    assert client.post('/api/router/access/release', json={'confirmed': True}).status_code == 401
    response = client.post('/api/router/access/release', json={'confirmed': True}, headers=HEADERS)
    assert response.status_code == 200 and response.json()['router_logout_verified']
    adapter.snapshot = forbidden
    with controller.sessions.begin() as db:
        db.add(Schedule(mac=MAC, action='block', timezone='UTC', next_run=(datetime.now(timezone.utc)-timedelta(seconds=5)).isoformat()))
    await controller.tick()
    assert not await controller.sync()
    assert client.post('/api/sync', headers=HEADERS).status_code == 200
    assert client.post(f'/api/devices/{MAC}/access', json={'blocked': True, 'confirmed': True}, headers=HEADERS).status_code == 409
    dashboard = client.get('/api/dashboard', headers=HEADERS).json()
    assert dashboard['router_access_paused'] and dashboard['stale'] and not dashboard['monitoring']
    assert dashboard['router_online'] is None and dashboard['last_sync'] == last_sync
    assert not client.get('/api/settings', headers=HEADERS).json()['writes_enabled']
    assert client.get('/api/devices', headers=HEADERS).status_code == 200
    assert client.get('/api/adguard', headers=HEADERS).status_code == 200
    await controller.release_router_access()
    restarted = Controller(controller.settings, adapter, controller.sessions)
    assert restarted.router_access_status()['router_access_paused']
    await restarted.tick()
    await restarted.adguard.close()
    assert calls == ['logout'] and not adapter.commands
    with controller.sessions() as db:
        assert 'router_offline' not in list(db.scalars(select(Event.kind)))
        assert db.scalar(select(Schedule)).enabled


async def test_resume_skips_missed_schedules(system):
    _, controller, adapter = system
    await controller.sync()
    await controller.release_router_access()
    with controller.sessions.begin() as db:
        db.add(Schedule(mac=MAC, action='block', timezone='UTC', next_run=(datetime.now(timezone.utc)-timedelta(seconds=30)).isoformat()))
    calls = []
    async def resume(): calls.append('resume')
    adapter.resume = resume
    await controller.resume_router_access()
    assert calls == ['resume'] and not controller.router_access_status()['router_access_paused']
    assert controller.health['router_online']
    with controller.sessions() as db:
        schedule = db.scalar(select(Schedule))
        assert not schedule.enabled and schedule.status == 'skipped_while_access_released'
    assert not adapter.commands


async def test_failed_logout_and_resume_leave_monitoring_paused(system):
    _, controller, adapter = system
    async def fail(): raise UnavailableError('test connection failed')
    adapter.logout = fail
    status = await controller.release_router_access()
    assert status['router_access_paused'] and not status['router_logout_verified']
    assert status['router_access_error'] == 'router_unavailable'
    adapter.resume = fail
    with pytest.raises(UnavailableError):
        await controller.resume_router_access()
    assert controller.router_access_status()['router_access_paused']


@pytest.mark.parametrize('actually_logs_out', [True, False])
async def test_firmware_logout_never_renews_the_session(tmp_path, actually_logs_out):
    calls = []
    def handler(request):
        calls.append((request.method, request.url.path))
        if request.url.path == '/logout.cgi':
            assert request.method == 'POST'
            return httpx.Response(200, text='logged out')
        if request.url.path == DEVICE_PATH:
            return httpx.Response(200, text="<script>var pageName = '/'; top.location.replace(pageName);</script>" if actually_logs_out else FIXTURE)
        raise AssertionError('Logout attempted unexpected request or authentication')
    adapter = HuaweiHG8546M(settings(tmp_path), httpx.MockTransport(handler))
    adapter.authenticated = True
    adapter.client.cookies.set('Cookie', 'test-session')
    if actually_logs_out:
        assert await adapter.logout()
        assert not adapter.authenticated and not list(adapter.client.cookies.jar)
    else:
        with pytest.raises(VerificationError): await adapter.logout()
    assert calls == [('POST', '/logout.cgi'), ('POST', DEVICE_PATH)]
    await adapter.close()
