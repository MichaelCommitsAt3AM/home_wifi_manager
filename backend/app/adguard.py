"""Read-only AdGuard Home status and DNS probes; never changes the existing service."""
import asyncio
import time
import socket
from datetime import datetime, timezone
import httpx
import dns.asyncresolver
import dns.exception
import dns.resolver


async def dns_probe(host):
    result = {'dns_reachable': False, 'normal_resolution': False, 'blocking_observed': None,
              'test_domain': 'doubleclick.net', 'scope': 'server_dns_probe'}
    try:
        loop = asyncio.get_running_loop()
        addresses = await loop.getaddrinfo(host, 53, family=socket.AF_INET, type=socket.SOCK_DGRAM)
        resolver = dns.asyncresolver.Resolver(configure=False)
        resolver.nameservers = [addresses[0][4][0]]
        resolver.timeout, resolver.lifetime = 2, 4
        answer = await resolver.resolve('example.com', 'A')
        result['normal_resolution'] = any(str(a) != '0.0.0.0' for a in answer)
        result['dns_reachable'] = True
        try:
            blocked = await resolver.resolve('doubleclick.net', 'A')
            result['blocking_observed'] = any(str(a) == '0.0.0.0' for a in blocked)
        except dns.resolver.NXDOMAIN:
            result['blocking_observed'] = True
        except dns.resolver.NoAnswer:
            result['blocking_observed'] = None
    except (OSError, dns.exception.DNSException):
        result['error'] = 'dns_probe_failed'
    return result


class AdGuard:
    def __init__(self, settings, transport=None, probe=dns_probe):
        self.settings = settings
        self.probe = probe
        self.lock = asyncio.Lock()
        self.cached = {'configured': bool(settings.adguard_url), 'online': None}
        self.checked = 0.0
        self.auth_rejected = False
        self.client = httpx.AsyncClient(timeout=8, trust_env=False, follow_redirects=False, transport=transport)

    async def close(self):
        await self.client.aclose()

    async def status(self):
        if not self.settings.adguard_url:
            return {'configured': False, 'online': None}
        async with self.lock:
            if self.checked and time.monotonic() - self.checked < 60:
                return self.cached
            result = {'configured': True, 'online': False, 'checked_at': datetime.now(timezone.utc).isoformat(),
                      'scope': 'AdGuard Home service and server DNS path; not all client devices',
                      'authentication_required': False}
            result.update(await self.probe(self.settings.adguard_dns_host))
            if self.auth_rejected:
                result['authentication_required'] = True
                result['error'] = 'adguard_authentication_failed'
                self.cached, self.checked = result, time.monotonic()
                return result
            if not self.settings.adguard_username or not self.settings.adguard_password.get_secret_value():
                result['authentication_required'] = True
                result['error'] = 'adguard_credentials_required'
            else:
                try:
                    auth = httpx.BasicAuth(self.settings.adguard_username, self.settings.adguard_password.get_secret_value())
                    base = self.settings.adguard_url.rstrip('/')
                    data = {}
                    for name, route in [('status', '/control/status'), ('filtering', '/control/filtering/status'), ('stats', '/control/stats')]:
                        response = await self.client.get(base + route, auth=auth)
                        if response.status_code == 401:
                            self.auth_rejected = True
                            result['authentication_required'] = True
                            result['error'] = 'adguard_authentication_failed'
                            break
                        response.raise_for_status()
                        data[name] = response.json()
                    else:
                        status, filters, stats = data['status'], data['filtering'], data['stats']
                        if not isinstance(status.get('running'), bool) or not isinstance(status.get('protection_enabled'), bool) or not isinstance(filters.get('enabled'), bool):
                            raise ValueError('Unexpected AdGuard schema')
                        queries = int(stats.get('num_dns_queries', 0))
                        blocked = int(stats.get('num_blocked_filtering', 0))
                        active = [f for f in filters.get('filters', []) if f.get('enabled')]
                        # Only aggregate counts leave this adapter; browsing domains/query logs are not exposed.
                        result.update(online=True, running=status['running'], protection_enabled=status['protection_enabled'],
                                      filtering_enabled=filters['enabled'], version=status.get('version', ''),
                                      dns_port=status.get('dns_port', 53), queries=queries, blocked_queries=blocked,
                                      blocked_percent=round(100 * blocked / queries, 1) if queries else 0,
                                      avg_processing_ms=round(float(stats.get('avg_processing_time', 0)) * 1000, 2),
                                      enabled_filters=len(active), rules_count=sum(int(f.get('rules_count', 0)) for f in active),
                                      last_filter_update=max((f.get('last_updated', '') for f in active), default=''),
                                      statistics_window='AdGuard configured statistics window',
                                      observed_clients=len(stats.get('top_clients', [])))
                except (httpx.HTTPError, ValueError, KeyError, TypeError):
                    result['error'] = 'adguard_unavailable_or_invalid_response'
            self.cached, self.checked = result, time.monotonic()
            return result
