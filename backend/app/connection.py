"""Independent server-side connectivity observations and bounded throughput tests."""
import asyncio
import json
import socket
import ssl
import time
from datetime import datetime, timedelta, timezone
import httpx
from .database import Config, now
from .adapters.base import CapabilityError


def load(db, key, default):
    row = db.get(Config, key)
    return json.loads(row.value) if row else default


def save(db, key, value):
    db.merge(Config(key=key, value=json.dumps(value)))


async def tls_probe(ip, hostname):
    start = time.monotonic()
    try:
        async with asyncio.timeout(5):
            _, writer = await asyncio.open_connection(ip, 443, ssl=ssl.create_default_context(), server_hostname=hostname)
            elapsed = round((time.monotonic()-start)*1000, 1)
            writer.close()
            await writer.wait_closed()
            return elapsed
    except (OSError, TimeoutError):
        return None


async def connectivity_probe(router_url, dns_host):
    async def router():
        try:
            # A fresh unauthenticated client must never reclaim a dashboard session.
            async with httpx.AsyncClient(timeout=4, trust_env=False, follow_redirects=False) as client:
                r = await client.get(router_url + '/')
                return r.status_code < 500
        except httpx.HTTPError:
            return False
    async def dns():
        import dns.asyncresolver
        import dns.exception
        try:
            resolver = dns.asyncresolver.Resolver(configure=not bool(dns_host))
            if dns_host:
                addresses = await asyncio.wait_for(asyncio.get_running_loop().getaddrinfo(dns_host, 53, family=socket.AF_INET), 4)
                resolver.nameservers = [addresses[0][4][0]]
            resolver.timeout, resolver.lifetime = 2, 4
            answer = await resolver.resolve('example.com', 'A')
            return any(str(a) != '0.0.0.0' for a in answer)
        except (OSError, TimeoutError, dns.exception.DNSException):
            return False
    reachable, resolved, cf, google = await asyncio.gather(router(), dns(), tls_probe('1.1.1.1', 'cloudflare-dns.com'), tls_probe('8.8.8.8', 'dns.google'))
    timings = [x for x in (cf, google) if x is not None]
    return {'router_reachable': reachable, 'internet_reachable': bool(timings), 'dns_working': resolved,
            'tls_connect_ms': min(timings) if timings else None}


class ConnectionMonitor:
    def __init__(self, settings, sessions, probe=connectivity_probe):
        self.settings, self.sessions, self.probe = settings, sessions, probe
        self.lock = asyncio.Lock()
        self.next_check = 0.0

    def report(self):
        with self.sessions() as db:
            result = load(db, 'connection_health', {'state': 'checking', 'outages': []})
        result['stale'] = not result.get('checked_at') or (datetime.now(timezone.utc)-datetime.fromisoformat(result['checked_at'])).total_seconds() > 90
        return result

    async def check(self, force=False):
        async with self.lock:
            if time.monotonic() < self.next_check and not force:
                return self.report()
            # Explicit checks are also bounded to avoid repeated external requests.
            if force and self.next_check-time.monotonic() > 25:
                return self.report()
            sample = await self.probe(self.settings.router_url, self.settings.adguard_dns_host if self.settings.adguard_url else '')
            self.next_check = time.monotonic()+30
            return self.record(sample)

    def record(self, sample, at=None):
        at = at or now()
        observed = ('router_unreachable' if not sample['router_reachable'] else
                    'internet_down' if not sample['internet_reachable'] else
                    'dns_failure' if not sample['dns_working'] else 'healthy')
        with self.sessions.begin() as db:
            state = load(db, 'connection_health', {'state': 'checking', 'outages': []})
            # An unobserved server outage is not counted as measured network downtime.
            if state.get('checked_at') and (datetime.fromisoformat(at)-datetime.fromisoformat(state['checked_at'])).total_seconds() > 90:
                for outage in state['outages']:
                    if not outage.get('ended_at'):
                        outage.update(ended_at=state['checked_at'], end_reason='monitoring_gap')
                state.update(state='checking', candidate=None, candidate_count=0)
            if state.get('candidate') == observed:
                state['candidate_count'] = state.get('candidate_count', 0)+1
            else:
                state.update(candidate=observed, candidate_count=1, candidate_since=at)
            if observed != state['state'] and state['candidate_count'] >= 2:
                for outage in state['outages']:
                    if not outage.get('ended_at'):
                        outage.update(ended_at=state['candidate_since'], end_reason='observed_transition')
                if observed != 'healthy':
                    state['outages'].insert(0, {'kind': observed, 'started_at': state['candidate_since'], 'ended_at': None})
                state['state'] = observed
                state['since'] = state['candidate_since']
            cutoff = (datetime.fromisoformat(at)-timedelta(days=self.settings.retention_days)).isoformat()
            state['outages'] = [x for x in state['outages'] if not x.get('ended_at') or x['ended_at'] >= cutoff][:100]
            state.update(sample, checked_at=at)
            save(db, 'connection_health', state)
        return self.report()


class SpeedTests:
    """One test at a time; fixed HTTPS targets, no arbitrary fetches or public IP logs."""
    def __init__(self, sessions, runner=None):
        self.sessions, self.runner = sessions, runner or self.measure
        self.lock = asyncio.Lock()
        self.task = None

    def report(self):
        with self.sessions() as db:
            return load(db, 'speed_test', {'state': 'idle'})

    async def start(self):
        async with self.lock:
            if self.task and not self.task.done():
                raise CapabilityError('A server speed test is already running')
            previous = self.report()
            if previous.get('started_at') and (datetime.now(timezone.utc)-datetime.fromisoformat(previous['started_at'])).total_seconds() < 60:
                raise CapabilityError('Wait one minute between speed tests')
            result = {'state': 'running', 'started_at': now(), 'provider': 'Cloudflare', 'scope': 'home_server', 'max_transfer_bytes': 30000000}
            with self.sessions.begin() as db: save(db, 'speed_test', result)
            self.task = asyncio.create_task(self.run(result))
            return result

    async def run(self, result):
        try:
            async with asyncio.timeout(70):
                result.update(await self.runner())
            result['state'] = 'completed'
        except asyncio.CancelledError:
            result.update(state='interrupted', error='Server stopped before the test completed')
            raise
        except Exception:
            result.update(state='failed', error='Speed test could not complete; try again later')
        finally:
            result['finished_at'] = now()
            with self.sessions.begin() as db: save(db, 'speed_test', result)

    async def close(self):
        if self.task and not self.task.done():
            self.task.cancel()
            try: await self.task
            except asyncio.CancelledError: pass

    def recover(self):
        with self.sessions.begin() as db:
            previous = load(db, 'speed_test', {'state': 'idle'})
            if previous['state'] == 'running':
                previous.update(state='interrupted', error='Server restarted before the test completed', finished_at=now())
                save(db, 'speed_test', previous)

    @staticmethod
    async def measure():
        async with httpx.AsyncClient(timeout=httpx.Timeout(20, connect=8), trust_env=False, follow_redirects=False, headers={'Cache-Control': 'no-store'}) as client:
            latency = []
            for _ in range(3):
                start = time.monotonic()
                r = await client.get('https://speed.cloudflare.com/__down', params={'bytes': 0})
                r.raise_for_status()
                latency.append((time.monotonic()-start)*1000)
            start = time.monotonic()
            received = 0
            async with client.stream('GET', 'https://speed.cloudflare.com/__down', params={'bytes': 25000000}) as response:
                response.raise_for_status()
                async for chunk in response.aiter_bytes():
                    received += len(chunk)
                    if received > 25000000: raise ValueError('Unexpected test response')
            down_time = time.monotonic()-start
            if received != 25000000: raise ValueError('Incomplete download')
            payload = b'0'*5000000
            start = time.monotonic()
            r = await client.post('https://speed.cloudflare.com/__up', content=payload, headers={'Content-Type': 'application/octet-stream'})
            r.raise_for_status()
            up_time = time.monotonic()-start
            return {'download_mbps': round(received*8/down_time/1e6, 2), 'upload_mbps': round(len(payload)*8/up_time/1e6, 2),
                    'latency_ms': round(sorted(latency)[1], 1), 'transfer_bytes': received+len(payload)}
