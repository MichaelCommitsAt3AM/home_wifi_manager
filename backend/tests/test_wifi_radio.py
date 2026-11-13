import httpx
import pytest
from urllib.parse import parse_qsl
from app.adapters.base import CapabilityError, VerificationError, ParseError
from app.adapters.huawei_hg8546m import HuaweiHG8546M, parse_radio
from test_system import system, settings, HEADERS, FIXTURE

DOMAIN = 'InternetGatewayDevice\\x2eLANDevice\\x2e1\\x2eWLANConfiguration\\x2e1'
RADIO_PAGE = ('var WlanBasicArr = new Array(new stWlanWifi("' + DOMAIN + '","ath0","1","Net","{std}","{ch}","100","KE","{auto}","1","","1","0","0","1","1","\\x2d1","\\x2d88","1","0","1","1","","0","0"),null);'
              'var WlanAdvs = new Array(new stWlanAdv("' + DOMAIN + '\\x2eX\\x5fHW\\x5fAdvanceConf","1","100","2346","2346"),null);'
              '<input type="hidden" id="hwonttoken" name="onttoken" value="radiotoken">')


def radio_adapter(tmp_path, verified=True, apply=True):
    state = {'std': '11n', 'ch': '11', 'auto': '0', 'posts': []}

    def handler(request):
        path = request.url.path
        if path.endswith('GetRandCount.asp'):
            return httpx.Response(200, text='abc123')
        if path.endswith('/login.cgi'):
            return httpx.Response(200, headers={'set-cookie': 'Cookie=s; Path=/'})
        if path.endswith('GetLanUserDevInfo.asp'):
            return httpx.Response(200, text=FIXTURE)
        if path.endswith('/set.cgi'):
            body = dict(parse_qsl(request.content.decode()))
            state['posts'].append((str(request.url), body, list(body)))
            if apply:
                auto = body['y.AutoChannelEnable']
                state.update(std=body['y.X_HW_Standard'], auto=auto, ch='6' if auto == '1' else body['y.Channel'])
            return httpx.Response(200, text='ok')
        if path.endswith('WlanAdvance.asp'):
            return httpx.Response(200, text=RADIO_PAGE.format(**state))
        return httpx.Response(404)
    adapter = HuaweiHG8546M(settings(tmp_path, writes_enabled=True, verified_radio_writes=verified),
                            httpx.MockTransport(handler))
    adapter.radio_settle_seconds = 0
    return adapter, state


async def test_radio_read_and_verified_write(tmp_path):
    adapter, state = radio_adapter(tmp_path)
    radio = await adapter.get_radio()
    assert radio['standard'] == '11n' and radio['channel'] == 11 and not radio['auto_channel']
    assert {s['value'] for s in radio['standards']} >= {'11n', '11bgn'}
    result = await adapter.set_radio('11bgn', 6)
    assert result['standard'] == '11bgn' and result['channel'] == 6
    url, body, order = state['posts'][0]
    assert 'X_HW_AdvanceConf' in url and body['y.AutoChannelEnable'] == '0' and body['x.BeaconPeriod'] == '100'
    assert order[-1] == 'x.X_HW_Token' and body['x.X_HW_Token'] == 'radiotoken'
    await adapter.set_radio('11bgn', 6)
    assert len(state['posts']) == 1  # already in the requested state
    auto = await adapter.set_radio(None, 0)
    assert auto['auto_channel'] and state['posts'][-1][1]['y.Channel'] == '0'
    await adapter.close()


async def test_radio_guards(tmp_path):
    adapter, _ = radio_adapter(tmp_path, verified=False)
    with pytest.raises(CapabilityError):
        await adapter.set_radio('11bgn', None)
    await adapter.close()
    adapter, state = radio_adapter(tmp_path)
    for std, ch in (('11ac', None), (None, 14)):
        with pytest.raises(CapabilityError):
            await adapter.set_radio(std, ch)
    assert not state['posts']
    await adapter.close()
    adapter, _ = radio_adapter(tmp_path, apply=False)
    with pytest.raises(VerificationError):
        await adapter.set_radio(None, 1)
    await adapter.close()
    with pytest.raises(ParseError):
        parse_radio(RADIO_PAGE.format(std='11x', ch='1', auto='0'))


def test_radio_api(system):
    client, controller, adapter = system
    assert client.get('/api/wifi/radio').status_code == 401
    assert client.get('/api/wifi/radio', headers=HEADERS).json()['standard'] == '11n'

    def put(**body):
        return client.put('/api/wifi/radio', json=body, headers=HEADERS)
    assert put(channel=6, confirmed=False).status_code == 422
    assert put(confirmed=True).status_code == 422
    assert put(channel=99, confirmed=True).status_code == 422
    assert put(standard='11ac', confirmed=True).status_code == 422
    assert put(channel=6, confirmed=True).status_code == 409  # radio writes not validated yet
    controller.settings.verified_radio_writes = True
    r = put(standard='11bgn', channel=6, confirmed=True)
    assert r.status_code == 200 and r.json()['standard'] == '11bgn' and adapter.radio['channel'] == 6
    assert 'wifi_radio_changed' in [e['kind'] for e in client.get('/api/activity', headers=HEADERS).json()]


def test_radio_parser_ignores_duplicate_and_unrelated_records():
    page = RADIO_PAGE.format(std='11n', ch='11', auto='0')
    record = page.split('new Array(')[1].split(',null)')[0]
    other = record.replace('WLANConfiguration\x2e1"', 'WLANConfiguration\x2e5"', 1)
    combined = page + 'var A = new Array(' + record + ',null);var B = new Array(' + other + ',null);'
    assert parse_radio(combined)['channel'] == 11
    with pytest.raises(ParseError) as error:
        parse_radio('<title>Waiting</title>')
    assert '0 usable' in str(error.value)
