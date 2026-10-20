import asyncio
from datetime import datetime, timedelta, timezone
import httpx
import pytest
import json
from urllib.parse import parse_qsl
from fastapi.testclient import TestClient
from sqlalchemy import select
from app.core import Settings
from app.database import Base, Device, Event, Schedule
from app.main import create_app
from app.services import recurring_next
from app.adapters.mock_router import MockRouter
from app.adapters.base import AuthenticationError, ParseError, CapabilityError, VerificationError
from app.adapters.base import Observation
from app.adapters.huawei_hg8546m import HuaweiHG8546M, parse_devices, parse_filters, normalize_mac

MAC = '02:00:00:00:00:02'
TOKEN = 'test-token-' + 'x' * 32
HEADERS = {'Authorization': 'Bearer ' + TOKEN}
FIXTURE = 'var UserDevinfo = new Array(new USERDevice("InternetGatewayDevice.LANDevice.1.X_HW_UserDev.1","192\\x2e168\\x2e100\\x2e50","02\\x3a00\\x3a00\\x3a00\\x3a00\\x3a02","LAN1","DHCP","","Online","ETH","0","Test\\x20device","1","1","0"),null);'


def settings(tmp_path, **kwargs):
    return Settings(_env_file=None, database_url=f'sqlite:///{tmp_path}/test.db', api_token=TOKEN,
                    router_password='test-password', protected_ips='', **kwargs)


@pytest.fixture
def system(tmp_path):
    adapter = MockRouter()
    app = create_app(settings(tmp_path, writes_enabled=True, verified_writes=True), adapter, False)
    c = app.state.controller
    Base.metadata.create_all(c.sessions.kw['bind'])
    with TestClient(app) as client:
        yield client, c, adapter


def test_parser_and_deduplication():
    assert parse_devices(FIXTURE)[0].hostname == 'Test device'
    assert parse_devices(FIXTURE)[0].mac == MAC
    duplicate = FIXTURE.replace(',null);', ',' + FIXTURE.split('new Array(')[1])
    assert len(parse_devices(duplicate)) == 1
    with pytest.raises(ParseError):
        parse_devices('<html>login</html>')
    with pytest.raises(ParseError):
        parse_devices(FIXTURE.replace('Online', 'Unexpected'))
    with pytest.raises(ValueError):
        normalize_mac('ff:ff:ff:ff:ff:ff')


def test_filter_policy_guard():
    empty = "var enableFilter = '1'; var Mode = '0'; var MacFilter = new Array(null);"
    assert parse_filters(empty, 'wired') == []
    assert parse_filters(empty + "function helper() { record = new stMacFilter('',''); }", 'wired') == []
    with pytest.raises(CapabilityError):
        parse_filters(empty.replace("Mode = '0'", "Mode = '1'"), 'wired')


def test_api_auth_validation_and_confirmation(system):
    client, _, _ = system
    assert client.get('/health').status_code == 200
    assert client.get('/api/devices').status_code == 401
    assert client.get('/api/devices', headers={'Authorization': 'Bearer wrong'}).status_code == 401
    assert client.post('/api/sync', headers=HEADERS).status_code == 200
    assert client.get('/api/devices/bad', headers=HEADERS).status_code == 422
    assert client.post(f'/api/devices/{MAC}/access', json={'blocked': True, 'confirmed': False}, headers=HEADERS).status_code == 422
    assert client.put('/api/settings', json={'poll_seconds': 1}, headers=HEADERS).status_code == 422
    assert client.put('/api/settings', json={'poll_seconds': 60, 'notification_categories':['bad']}, headers=HEADERS).status_code == 422


def test_manage_protection_and_history(system):
    client, controller, adapter = system
    client.post('/api/sync', headers=HEADERS)
    response = client.post(f'/api/devices/{MAC}/access', json={'blocked':True, 'confirmed':True}, headers=HEADERS)
    assert response.status_code == 200 and response.json()['blocked']
    response = client.post(f'/api/devices/{MAC}/access', json={'blocked':False, 'confirmed':True}, headers=HEADERS)
    assert response.status_code == 200 and not response.json()['blocked']
    client.patch(f'/api/devices/{MAC}', json={'alias':'Laptop', 'protected':True}, headers=HEADERS)
    assert client.post(f'/api/devices/{MAC}/access', json={'blocked':True, 'confirmed':True}, headers=HEADERS).status_code == 403
    kinds = [e['kind'] for e in client.get('/api/activity', headers=HEADERS).json()]
    assert 'device_blocked' in kinds and 'device_unblocked' in kinds


async def test_monitoring_debounce_and_outage(system):
    _, controller, adapter = system
    await controller.sync()
    adapter.devices[0].online = False
    await controller.sync()
    with controller.sessions() as db:
        assert db.get(Device, MAC).online
    await controller.sync()
    with controller.sessions() as db:
        assert not db.get(Device, MAC).online
    adapter.unavailable = True
    assert not await controller.sync()
    assert not await controller.sync()
    adapter.unavailable = False
    adapter.devices[0].online = True
    await controller.sync()
    with controller.sessions() as db:
        kinds = list(db.scalars(select(Event.kind)))
        assert kinds.count('router_offline') == 1
        assert kinds.count('router_restored') == 1
        assert kinds.count('device_disconnected') == 1


async def test_schedule_recovery_and_manual_pause(system):
    client, controller, adapter = system
    await controller.sync()
    with controller.sessions.begin() as db:
        db.add(Schedule(mac=MAC, action='block', timezone='UTC', next_run=(datetime.now(timezone.utc)-timedelta(seconds=5)).isoformat()))
    await controller.run_schedules()
    assert adapter.commands[-1] == (MAC, True)
    await controller.run_schedules()
    assert len(adapter.commands) == 1
    with controller.sessions.begin() as db:
        db.add(Schedule(mac=MAC, action='block', timezone='UTC', next_run=(datetime.now(timezone.utc)-timedelta(hours=1)).isoformat()))
    await controller.run_schedules()
    assert len(adapter.commands) == 1
    response = client.post('/api/schedules', json={'mac':MAC, 'action':'unblock', 'run_at':(datetime.now(timezone.utc)+timedelta(minutes=30)).isoformat()}, headers=HEADERS)
    assert response.status_code == 200
    await controller.manage(MAC, False)
    with controller.sessions() as db:
        assert not any(s.enabled for s in db.scalars(select(Schedule)))


def test_persistence_and_timezone(system):
    client, controller, _ = system
    client.post('/api/sync', headers=HEADERS)
    client.patch(f'/api/devices/{MAC}', json={'alias':'Saved name'}, headers=HEADERS)
    from app.database import database
    engine, session = database(controller.settings.database_url)
    with session() as db:
        assert db.get(Device, MAC).alias == 'Saved name'
    engine.dispose()
    result = recurring_next('20:00', '0,1,2,3,4,5,6', 'Africa/Nairobi', datetime(2026,10,19,16,tzinfo=timezone.utc))
    assert result.startswith('2026-10-19T17:00')


async def test_auth_session_renewal_and_no_write_retry(tmp_path):
    state = {'auth':0, 'expire':False, 'writes':0}
    def handler(request):
        if request.url.path == '/asp/GetRandCount.asp':
            return httpx.Response(200, text='abc123')
        if request.url.path == '/login.cgi':
            state['auth'] += 1
            return httpx.Response(200, headers={'set-cookie':'Cookie=session; Path=/'})
        if request.url.path.endswith('GetLanUserDevInfo.asp'):
            if state['expire']:
                state['expire'] = False
                return httpx.Response(200, text="<title>Waiting...</title><script>var pageName = '/'; top.location.replace(pageName);</script>")
            return httpx.Response(200, text=FIXTURE)
        if request.url.path.endswith('/add.cgi'):
            state['writes'] += 1
            return httpx.Response(200, text='ok')
        if 'macfilter' in request.url.path:
            arr = 'MacFilterSrc' if 'wlanmacfilter' in request.url.path else 'MacFilter'
            return httpx.Response(200, text=f"var enableFilter = '1'; var Mode = '0'; var {arr} = new Array(null); <input name=\"onttoken\" value=\"testtoken\">")
        return httpx.Response(404)
    adapter = HuaweiHG8546M(settings(tmp_path, writes_enabled=True, verified_writes=True), httpx.MockTransport(handler))
    await adapter.authenticate()
    state['expire'] = True
    async with adapter.lock:
        assert 'USERDevice' in await adapter._read('/html/bbsp/common/GetLanUserDevInfo.asp', True)
    assert state['auth'] == 2
    with pytest.raises(VerificationError):
        await adapter.set_blocked(parse_devices(FIXTURE)[0], True)
    assert state['writes'] == 1
    await adapter.close()


async def test_auth_rejection_is_latched(tmp_path):
    attempts = []
    def handler(request):
        attempts.append(request.url.path)
        return httpx.Response(200, text='abc123' if 'GetRandCount' in request.url.path else '<input id="txt_Username">')
    adapter = HuaweiHG8546M(settings(tmp_path), httpx.MockTransport(handler))
    for _ in range(2):
        with pytest.raises(AuthenticationError):
            await adapter.authenticate()
    assert attempts.count('/login.cgi') == 1
    await adapter.close()


async def test_notification_outbox(system, monkeypatch):
    _, controller, _ = system
    controller.settings.ntfy_url = 'https://ntfy.example.test/private'
    with controller.sessions.begin() as db:
        event = controller.event(db, 'unknown_device', MAC)
        db.flush()
        eid = event.id
    calls = []
    async def post(self, url, **kwargs):
        calls.append(kwargs)
        return httpx.Response(200, request=httpx.Request('POST',url))
    monkeypatch.setattr(httpx.AsyncClient, 'post', post)
    await controller.notify()
    await controller.notify()
    assert len(calls) == 1
    assert MAC not in calls[0]['content']
    with controller.sessions() as db:
        assert db.get(Event, eid).notified


def test_alembic_schema_matches_models(tmp_path, monkeypatch):
    from alembic.config import Config as AlembicConfig
    from alembic import command
    from alembic.migration import MigrationContext
    from alembic.autogenerate import compare_metadata
    from sqlalchemy import create_engine
    url = f'sqlite:///{tmp_path}/migration.db'
    monkeypatch.setenv('DATABASE_URL', url)
    monkeypatch.setenv('API_TOKEN', TOKEN)
    monkeypatch.setenv('ROUTER_PASSWORD', 'test-only')
    command.upgrade(AlembicConfig('alembic.ini'), 'head')
    engine = create_engine(url)
    with engine.connect() as connection:
        assert compare_metadata(MigrationContext.configure(connection), Base.metadata) == []
        assert connection.exec_driver_sql('PRAGMA integrity_check').scalar() == 'ok'
    engine.dispose()


@pytest.mark.parametrize('scope', ['wired', 'wireless'])
async def test_native_form_order_and_verified_block_unblock(tmp_path, scope):
    state = {'blocked': False, 'actions': []}
    folder = 'wlanmacfilter' if scope == 'wireless' else 'macfilter'
    object_name = 'WLANMacFilter' if scope == 'wireless' else 'MacFilter'
    domain = 'InternetGatewayDevice.X_HW_Security.' + object_name + '.1'

    def handler(request):
        path = request.url.path
        if path == '/asp/GetRandCount.asp':
            return httpx.Response(200, text='abc123')
        if path == '/login.cgi':
            return httpx.Response(200, headers={'set-cookie': 'Cookie=session; Path=/'})
        if path.endswith('/GetLanUserDevInfo.asp'):
            return httpx.Response(200, text=FIXTURE)
        if path.endswith('/add.cgi') or path.endswith('/del.cgi'):
            values = parse_qsl(request.content.decode(), keep_blank_values=True)
            fields = dict(values)
            assert values[-1] == ('x.X_HW_Token', 'fresh-csrf')
            assert request.headers['Origin'] == 'http://192.168.100.1'
            assert request.headers['Referer'] == f'http://192.168.100.1/html/bbsp/{folder}/{folder}.asp'
            assert b'RequestFile=html/bbsp/' in request.url.query
            if path.endswith('/add.cgi'):
                assert fields['x.SourceMACAddress'] == MAC
                assert [key for key, _ in values] == (['x.SourceMACAddress', 'x.SSIDName', 'x.Enable', 'x.X_HW_Token'] if scope == 'wireless' else ['x.SourceMACAddress', 'x.X_HW_Token'])
                if scope == 'wireless':
                    assert fields['x.SSIDName'] == 'SSID-1' and fields['x.Enable'] == '1'
                state['blocked'] = True
                state['actions'].append('block')
            else:
                assert fields[domain] == ''
                state['blocked'] = False
                state['actions'].append('unblock')
            return httpx.Response(200, text='ok')
        if path.endswith('/macfilter.asp') or path.endswith('/wlanmacfilter.asp'):
            wireless = '/wlanmacfilter/' in path
            array = 'MacFilterSrc' if wireless else 'MacFilter'
            row = 'null'
            if state['blocked'] and wireless == (scope == 'wireless'):
                arguments = [domain, 'SSID-1', MAC] if wireless else [domain, MAC]
                row = 'new stMacFilter(' + ','.join(json.dumps(x) for x in arguments) + '),null'
            return httpx.Response(200, text=f"var enableFilter='1'; var Mode='0'; var {array}=new Array({row}); <input name=\"onttoken\" value=\"fresh-csrf\">")
        return httpx.Response(404)

    adapter = HuaweiHG8546M(settings(tmp_path, writes_enabled=True, verified_writes=True), httpx.MockTransport(handler))
    device = Observation(MAC, connection_type=scope, port='SSID1' if scope == 'wireless' else 'LAN1')
    await adapter.set_blocked(device, True)
    assert state['blocked']
    await adapter.set_blocked(device, False)
    assert not state['blocked'] and state['actions'] == ['block', 'unblock']
    await adapter.close()
