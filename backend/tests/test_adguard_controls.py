import json
import httpx
import pytest
from app.adguard import AdGuard, AdGuardControlError, normalize_domain
from app.adapters.base import CapabilityError
from test_adguard import config, probe
from test_system import system, HEADERS


def fake_adguard():
    state = {'rules':['! Existing owner rule','||blocked.example^','@@||external.example^'], 'enabled':True, 'duration':0, 'calls':[]}
    def handler(request):
        state['calls'].append((request.method, request.url.path))
        path = request.url.path
        if request.method == 'POST':
            body = json.loads(request.content)
            if path == '/control/protection': state.update(enabled=body['enabled'], duration=body['duration'])
            if path == '/control/filtering/set_rules': state['rules'] = body['rules']
            return httpx.Response(200,json={'updated':2} if path.endswith('/refresh') else {})
        if path == '/control/status': return httpx.Response(200,json={'running':True,'protection_enabled':state['enabled'],'protection_disabled_duration':state['duration']})
        if path == '/control/filtering/status': return httpx.Response(200,json={'enabled':True,'user_rules':state['rules'],'filters':[]})
        return httpx.Response(200,json={})
    return AdGuard(config(),httpx.MockTransport(handler),probe),state


async def test_native_pause_timer_resume_and_filter_refresh():
    a,s = fake_adguard()
    await a.protection(False,15)
    assert not s['enabled'] and s['duration'] == 900000
    assert not (await a.status())['protection_enabled']
    await a.protection(True,5)
    assert s['enabled'] and s['duration'] == 0
    assert (await a.status())['protection_enabled']
    assert await a.refresh_filters() == {'updated':2}
    await a.close()


async def test_domain_exceptions_preserve_unrelated_rules_and_only_remove_owned_rules():
    a,s = fake_adguard()
    baseline = list(s['rules'])
    result = await a.exception('Example.COM.',True)
    assert result['domains'] == ['example.com']
    assert s['rules'][:len(baseline)] == baseline
    assert (await a.exceptions())['domains'] == ['example.com']
    calls = len(s['calls'])
    await a.exception('example.com',True)
    assert all(method == 'GET' for method,_ in s['calls'][calls:])
    with pytest.raises(CapabilityError): await a.exception('external.example',True)
    await a.exception('external.example',False)
    assert '@@||external.example^' in s['rules']
    await a.exception('example.com',False)
    assert s['rules'] == baseline
    await a.close()


@pytest.mark.parametrize('domain',['https://example.com','*.example.com','127.0.0.1','example.com\n||bad^','localhost','-bad.example','example..com'])
def test_domain_validation(domain):
    with pytest.raises(ValueError): normalize_domain(domain)


def test_adguard_controls_require_authentication_and_confirmation(system):
    client,_,_ = system
    for path,body in [('/api/adguard/protection',{'enabled':False,'minutes':5,'confirmed':False}),
                      ('/api/adguard/filters/refresh',{'confirmed':False}),
                      ('/api/adguard/exceptions',{'domain':'example.com','allowed':True,'confirmed':False})]:
        assert client.post(path,json=body).status_code == 401
        assert client.post(path,json=body,headers=HEADERS).status_code == 422
    assert client.post('/api/adguard/protection',json={'enabled':False,'minutes':0,'confirmed':True},headers=HEADERS).status_code == 422
