"""AdGuard status, DNS probes, and serialized owner-confirmed controls."""
import asyncio
import time
import socket
import ipaddress
import re
from .adapters.base import RouterError, CapabilityError
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


class AdGuardControlError(RouterError):
    code = 'adguard_control_failed'


def normalize_domain(value):
    value = value.strip().rstrip('.').lower()
    try:
        value = value.encode('idna').decode('ascii')
        ipaddress.ip_address(value)
    except ValueError:
        if len(value) <= 253 and '.' in value and all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', label) for label in value.split('.')):
            return value
    raise ValueError('Enter a domain such as example.com, without a URL, IP address or wildcard')


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
                                      filtering_enabled=filters['enabled'], protection_disabled_duration=int(status.get('protection_disabled_duration', 0)), version=status.get('version', ''),
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


    async def _control(self, method, route, body=None):
        if not self.settings.adguard_url or self.auth_rejected:
            raise AdGuardControlError('AdGuard is unavailable or authentication needs attention')
        try:
            auth = httpx.BasicAuth(self.settings.adguard_username, self.settings.adguard_password.get_secret_value())
            response = await self.client.request(method, self.settings.adguard_url.rstrip('/')+'/control'+route, auth=auth, json=body)
            if response.status_code == 401:
                self.auth_rejected = True
            response.raise_for_status()
            return response
        except httpx.HTTPError as error:
            self.checked = 0
            raise AdGuardControlError('AdGuard did not confirm the request; refresh before retrying') from error

    async def protection(self, enabled, minutes):
        async with self.lock:
            self.checked = 0
            await self._control('POST', '/protection', {'enabled': enabled, 'duration': 0 if enabled else minutes*60000})
            try:
                state = (await self._control('GET', '/status')).json()
                if state.get('protection_enabled') is not enabled:
                    raise AdGuardControlError('AdGuard protection change was not verified')
            except (ValueError, TypeError) as error:
                raise AdGuardControlError('Unexpected AdGuard status') from error

    async def refresh_filters(self):
        async with self.lock:
            self.checked = 0
            response = await self._control('POST', '/filtering/refresh', {'whitelist': False})
            try:
                updated = response.json()['updated']
                if not isinstance(updated, int) or updated < 0: raise ValueError()
                return {'updated': updated}
            except (ValueError, KeyError, TypeError) as error:
                raise AdGuardControlError('Filter refresh response could not be verified') from error

    async def _rules(self):
        try:
            rules = (await self._control('GET', '/filtering/status')).json()['user_rules']
            if not isinstance(rules, list) or not all(isinstance(x, str) for x in rules): raise ValueError()
            return rules
        except (ValueError, KeyError, TypeError) as error:
            raise AdGuardControlError('Custom filtering rules could not be read safely') from error

    @staticmethod
    def managed_exceptions(rules):
        result = []
        for i, line in enumerate(rules[:-1]):
            if line.startswith('! Home WiFi allow: '):
                domain = line.removeprefix('! Home WiFi allow: ')
                try:
                    if normalize_domain(domain) == domain and rules[i+1] == '@@||'+domain+'^': result.append(domain)
                except ValueError: pass
        return sorted(set(result))

    async def exceptions(self):
        async with self.lock:
            return {'domains': self.managed_exceptions(await self._rules())}

    async def exception(self, domain, allowed):
        domain = normalize_domain(domain)
        marker, rule = '! Home WiFi allow: '+domain, '@@||'+domain+'^'
        async with self.lock:
            self.checked = 0
            original = await self._rules()
            managed = self.managed_exceptions(original)
            updated = list(original)
            if allowed and domain not in managed:
                if rule in original:
                    raise CapabilityError('This domain already has an external allow rule; manage it in AdGuard')
                updated.extend([marker, rule])
            elif not allowed and domain in managed:
                for i in range(len(updated)-2, -1, -1):
                    if updated[i:i+2] == [marker, rule]: del updated[i:i+2]
            if updated != original:
                # Detect external edits before replacing the list; preserve every unrelated rule.
                if await self._rules() != original:
                    raise CapabilityError('AdGuard rules changed externally; refresh before retrying')
                await self._control('POST', '/filtering/set_rules', {'rules': updated})
                if await self._rules() != updated:
                    raise AdGuardControlError('Domain exception update was not verified')
            return {'domains': self.managed_exceptions(updated)}
