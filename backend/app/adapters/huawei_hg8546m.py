"""Narrow adapter for the inspected HG8546M V3R017C10S125 firmware.

Never evaluates router JavaScript. Reads and mutations share one session lock.
Mutations are never blindly retried, including after a timeout.
"""
import asyncio
import base64
import ipaddress
import re
from urllib.parse import urlencode
import httpx
from .base import (RouterAdapter, Observation, Restriction, AuthenticationError,
                   UnavailableError, CapabilityError, VerificationError, ParseError)

DEVICE_PATH = '/html/bbsp/common/GetLanUserDevInfo.asp'
STATUS_PATH = '/html/ssmp/deviceinfo/deviceinfo.asp'
FILTER_PATHS = {'wired': '/html/bbsp/macfilter/macfilter.asp',
                'wireless': '/html/bbsp/wlanmacfilter/wlanmacfilter.asp'}
MAC_PATTERN = re.compile(r'^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$')


def normalize_mac(value: str) -> str:
    value = value.lower().replace('-', ':')
    if not MAC_PATTERN.fullmatch(value) or int(value[:2], 16) & 1 or value == '00:00:00:00:00:00':
        raise ValueError('A unicast MAC address is required')
    return value


def js_string(value: str) -> str:
    # The firmware emits \xNN escapes; preserve UTF-8, reject unknown escapes.
    def decode(m):
        s = m.group(1)
        if s.startswith('x'):
            return chr(int(s[1:], 16))
        return {'n': '\n', 'r': '\r', 't': '\t', '\\': '\\', '"': '"', "'": "'", '/': '/'}[s]
    return re.sub(r'\\(x[0-9a-fA-F]{2}|[nrt\\"\'/])', decode, value)


def constructors(text: str, name: str, count: int) -> list[list[str]]:
    rows = []
    for match in re.finditer(r'new\s+' + re.escape(name) + r'\s*\(([^\r\n]*?)\)', text):
        body = match.group(1)
        values = re.findall(r'"((?:\\.|[^"\\])*)"', body)
        remainder = re.sub(r'"(?:\\.|[^"\\])*"', '', body)
        if len(values) != count or remainder.replace(',', '').strip():
            raise ParseError('Unexpected firmware record schema')
        rows.append([js_string(x) for x in values])
    return rows


def parse_devices(text: str) -> list[Observation]:
    data = re.search(r'var UserDevinfo = new Array\((.*?)\);', text, re.S)
    if not data:
        raise ParseError('Missing device array')
    if data.group(1).strip() not in ('', 'null') and 'new USERDevice(' not in data.group(1):
        raise ParseError('Unknown device record type')
    result = {}
    for row in constructors(text, 'USERDevice', 13):
        try:
            mac = normalize_mac(row[2])
            ip = str(ipaddress.ip_address(row[1])) if row[1] else None
        except ValueError as e:
            raise ParseError('Invalid router device identity') from e
        if row[6] not in ('Online', 'Offline'):
            raise ParseError('Unknown association state')
        dev = Observation(mac, ip, row[9], row[6] == 'Online',
                          {'ETH': 'wired', 'WIFI': 'wireless'}.get(row[7], 'unknown'),
                          row[3], row[5])
        if mac not in result or dev.online:
            result[mac] = dev
    return list(result.values())


def variable(text: str, name: str) -> str:
    m = re.search(r'var\s+' + re.escape(name) + r'\s*=\s*[\'"]([^\'"]*)[\'"]', text)
    if not m:
        raise ParseError('Missing firmware field: ' + name)
    return js_string(m.group(1))


def parse_filters(text: str, scope: str) -> list[Restriction]:
    array = 'MacFilterSrc' if scope == 'wireless' else 'MacFilter'
    data = re.search(r'var\s+' + array + r'\s*=\s*new Array\((.*?)\);', text, re.S)
    if not data:
        raise ParseError('Missing access-control array')
    if data.group(1).strip() not in ('', 'null') and 'new stMacFilter(' not in data.group(1):
        raise ParseError('Unknown restriction record type')
    enabled, mode = variable(text, 'enableFilter'), variable(text, 'Mode')
    if enabled != '1' or mode != '0':
        raise CapabilityError('Only an already enabled blacklist is supported; policy will not be changed')
    rows = constructors(data.group(1), 'stMacFilter', 3 if scope == 'wireless' else 2)
    restrictions = []
    for row in rows:
        expected = 'WLANMacFilter' if scope == 'wireless' else 'MacFilter'
        if not re.fullmatch(r'InternetGatewayDevice\.X_HW_Security\.' + expected + r'\.\d+', row[0]):
            raise ParseError('Invalid restriction domain')
        restrictions.append(Restriction(normalize_mac(row[-1]), row[0], scope,
                                        row[1] if scope == 'wireless' else ''))
    return restrictions


class HuaweiHG8546M(RouterAdapter):
    def __init__(self, settings, transport=None):
        self.settings = settings
        if settings.router_url != 'http://192.168.100.1':
            raise ValueError('This verified adapter is restricted to the inspected LAN router')
        self.client = httpx.AsyncClient(base_url=settings.router_url, timeout=12,
                                       follow_redirects=False, transport=transport, trust_env=False)
        self.lock = asyncio.Lock()
        self.authenticated = False
        self.auth_failed = False

    async def _request(self, method, path, data=None, headers=None):
        try:
            r = await self.client.request(method, path, data=data, headers=headers)
        except httpx.HTTPError as e:
            raise UnavailableError('Router connection failed') from e
        if r.status_code >= 500:
            raise UnavailableError('Router returned a server error')
        return r

    @staticmethod
    def _expired(r):
        return (r.status_code in (301, 302, 303, 401, 403) or
                'txt_Username' in r.text or 'window.location="/login.asp"' in r.text or
                "window.location='/login.asp'" in r.text or
                ("var pageName = '/';" in r.text and 'top.location.replace(pageName)' in r.text))

    async def _authenticate(self):
        if self.auth_failed:
            raise AuthenticationError('Authentication paused after rejection; check credentials and restart')
        self.client.cookies.clear()
        self.client.cookies.set('Cookie', 'body:Language:english:id=-1')
        r = await self._request('POST', '/asp/GetRandCount.asp', {})
        token = r.text.lstrip('\ufeff').strip()
        if not re.fullmatch(r'[a-zA-Z0-9]{1,128}', token):
            raise AuthenticationError('Invalid login challenge')
        login = await self._request('POST', '/login.cgi', {
            'UserName': self.settings.router_username,
            'PassWord': base64.b64encode(self.settings.router_password.get_secret_value().encode()).decode(),
            'x.X_HW_Token': token})
        if 'number of login users' in login.text.lower() or 'user has already logged in' in login.text.lower():
            self.auth_failed = True
            raise AuthenticationError('Router dashboard is in use. Log out there before resuming server access.')
        check = await self._request('POST', DEVICE_PATH, {})
        if self._expired(check) or 'var UserDevinfo = new Array(' not in check.text:
            self.auth_failed = True
            raise AuthenticationError('Router rejected authentication')
        self.authenticated = True

    async def authenticate(self):
        async with self.lock:
            await self._authenticate()

    async def resume(self):
        # An explicit owner action permits one retry after a latched rejection.
        async with self.lock:
            self.auth_failed = False
            await self._authenticate()

    async def logout(self):
        async with self.lock:
            if not self.authenticated:
                # Never acquire a session just to log out: a technician may own it.
                return False
            await self._request('POST', '/logout.cgi?RequestFile=html/logout.html', {},
                                headers={'Origin': self.settings.router_url,
                                         'Referer': self.settings.router_url + '/index.asp'})
            # Raw probe: _read would silently reauthenticate and reclaim the slot.
            check = await self._request('POST', DEVICE_PATH, {})
            if not self._expired(check):
                raise VerificationError('Router logout was not confirmed; monitoring remains paused')
            self.client.cookies.clear()
            self.authenticated = False
            self.auth_failed = False
            return True

    async def _read(self, path, post=False):
        if not self.authenticated:
            await self._authenticate()
        r = await self._request('POST' if post else 'GET', path, {} if post else None)
        if self._expired(r):
            self.authenticated = False
            await self._authenticate()
            r = await self._request('POST' if post else 'GET', path, {} if post else None)
        if self._expired(r):
            raise AuthenticationError('Router session could not be renewed')
        if r.status_code != 200:
            raise ParseError('Unexpected router HTTP status')
        return r.text

    async def _filters(self):
        pages, restrictions = {}, []
        for scope, path in FILTER_PATHS.items():
            pages[scope] = await self._read(path)
            restrictions.extend(parse_filters(pages[scope], scope))
        return pages, restrictions

    async def snapshot(self):
        async with self.lock:
            devices = parse_devices(await self._read(DEVICE_PATH, True))
            _, restrictions = await self._filters()
            page = await self._read(STATUS_PATH)
            info = constructors(page, 'stDeviceInfo', 10)
            if not info or info[0][3] != 'V3R017C10S125' or info[0][4] != 'HG8546M':
                raise CapabilityError('Firmware changed; adapter must be reviewed')
            return devices, restrictions, {
                'model': info[0][4], 'firmware': info[0][3], 'hardware': info[0][2],
                'cpu': variable(page, 'cpuUsed'), 'memory': variable(page, 'memUsed'),
                'uptime_seconds': None, 'internet_online': None,
                'block_supported': self.settings.writes_enabled and self.settings.verified_writes,
                'scheduling_supported': self.settings.writes_enabled and self.settings.verified_writes,
                'traffic_supported': False}

    async def set_blocked(self, device: Observation, blocked: bool):
        if not (self.settings.writes_enabled and self.settings.verified_writes):
            raise CapabilityError('Live writes await an authorized safe-device validation')
        mac = normalize_mac(device.mac)
        async with self.lock:
            pages, restrictions = await self._filters()
            existing = [r for r in restrictions if r.mac == mac]
            scope = device.connection_type
            if scope not in FILTER_PATHS:
                raise CapabilityError('Connection type is unknown')
            if blocked and scope == 'wireless' and not re.fullmatch(r'SSID[1-8]', device.port):
                raise CapabilityError('An active SSID association is required to block this device')
            if blocked and existing:
                # A restriction on a different SSID does not prove this association is blocked.
                if any(r.scope == scope and (scope == 'wired' or r.ssid == 'SSID-' + device.port[4:]) for r in existing):
                    return
            if not blocked and not existing:
                return
            targets = [scope] if blocked else list(dict.fromkeys(r.scope for r in existing))
            for target in targets:
                page = pages[target]
                match = re.search(r'name=[\'"]onttoken[\'"][^>]*value=[\'"]([^\'"]+)', page)
                if not match:
                    raise ParseError('Missing CSRF token')
                data = {}
                path = FILTER_PATHS[target].lstrip('/')
                obj = 'InternetGatewayDevice.X_HW_Security.' + ('WLANMacFilter' if target == 'wireless' else 'MacFilter')
                if blocked:
                    if target == 'wired' and sum(r.scope == target for r in restrictions) >= 8:
                        raise CapabilityError('Wired blacklist is full')
                    data['x.SourceMACAddress'] = mac
                    if target == 'wireless':
                        data.update({'x.SSIDName': 'SSID-' + device.port[4:], 'x.Enable': '1'})
                    action = 'add.cgi'
                else:
                    data.update({r.domain: '' for r in existing if r.scope == target})
                    action = 'del.cgi'
                # Match native form order, including the CSRF field added last.
                data['x.X_HW_Token'] = match.group(1)
                # webSubmitForm assigns a relative action inside the filter iframe.
                action_path = FILTER_PATHS[target].rsplit('/', 1)[0] + '/' + action
                response = await self._request('POST', action_path + '?' + urlencode({'x': obj, 'RequestFile': path}, safe='/'), data,
                                               headers={'Origin': self.settings.router_url,
                                                        'Referer': self.settings.router_url + FILTER_PATHS[target]})
                if self._expired(response):
                    raise VerificationError('Session expired during change; refresh actual state before retrying')
            # Always read actual access-control state; never trust HTTP 200 as success.
            _, actual = await self._filters()
            if blocked:
                verified = any(r.mac == mac and r.scope == scope and
                               (scope == 'wired' or r.ssid == 'SSID-' + device.port[4:]) for r in actual)
            else:
                verified = not any(r.mac == mac for r in actual)
            if not verified:
                raise VerificationError('Router did not confirm requested restriction state')

    async def close(self):
        await self.client.aclose()
