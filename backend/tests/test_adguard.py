import httpx
import pytest
from app.core import Settings
from app.adguard import AdGuard


def config():
    return Settings(_env_file=None, api_token='x'*32, router_password='test-only',
                    adguard_url='http://adguard.test:3000', adguard_username='test-user',
                    adguard_password='test-password')


async def probe(_):
    return {'dns_reachable': True, 'normal_resolution': True, 'blocking_observed': True}


async def test_aggregate_health_privacy_and_read_only_cache():
    calls = []
    def handler(request):
        calls.append(request)
        assert request.method == 'GET'
        assert request.headers.get('Authorization', '').startswith('Basic ')
        if request.url.path == '/control/status':
            return httpx.Response(200, json={'running': True, 'protection_enabled': True, 'version': 'v0.107.79'})
        if request.url.path == '/control/filtering/status':
            return httpx.Response(200, json={'enabled': True, 'filters': [{'enabled': True, 'rules_count': 12000, 'last_updated':'2026-10-19T12:00:00Z'}]})
        return httpx.Response(200, json={'num_dns_queries':100, 'num_blocked_filtering':25, 'avg_processing_time':.004,
                                        'top_queried_domains':[{'private.example':50}], 'top_clients':[{'192.168.100.50':10}]})
    adapter = AdGuard(config(), httpx.MockTransport(handler), probe)
    result = await adapter.status()
    assert result['online'] and result['protection_enabled'] and result['blocking_observed']
    assert result['blocked_percent'] == 25 and result['avg_processing_ms'] == 4
    assert result['rules_count'] == 12000
    assert 'private.example' not in str(result) and '192.168.100.50' not in str(result)
    await adapter.status()
    assert len(calls) == 3
    await adapter.close()


async def test_rejected_credentials_stop_authentication_retries():
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(401)
    adapter = AdGuard(config(), httpx.MockTransport(handler), probe)
    result = await adapter.status()
    assert result['authentication_required']
    assert result['normal_resolution']
    adapter.checked = 0
    await adapter.status()
    assert len(calls) == 1
    await adapter.close()


async def test_invalid_response_is_not_reported_as_healthy():
    adapter = AdGuard(config(), httpx.MockTransport(lambda _: httpx.Response(200, json={'unexpected': True})), probe)
    result = await adapter.status()
    assert not result['online']
    assert result['error'] == 'adguard_unavailable_or_invalid_response'
    await adapter.close()


async def test_disabled_and_unreachable_services():
    settings = config()
    settings.adguard_url = ''
    adapter = AdGuard(settings)
    assert not (await adapter.status())['configured']
    await adapter.close()
    def fail(request):
        raise httpx.ConnectError('offline')
    adapter = AdGuard(config(), httpx.MockTransport(fail), probe)
    assert not (await adapter.status())['online']
    await adapter.close()
