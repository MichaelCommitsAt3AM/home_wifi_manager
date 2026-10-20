"""Owner-authorized live validation, executed with the normal backend stopped.

Run inside a one-off backend container sharing its existing database. Test-only
flags are local to this process. Always attempt restoration of the original
unrestricted state, including after an ambiguous mutation response.
"""
import argparse
import asyncio
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from app.core import Settings
from app.database import database, Device
from app.services import Controller
from app.adapters.huawei_hg8546m import HuaweiHG8546M, normalize_mac


class ResponseText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.hidden = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.hidden += 1

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


class DiagnosticAdapter(HuaweiHG8546M):
    async def _request(self, method, path, data=None, headers=None):
        response = await super()._request(method, path, data, headers=headers)
        if '/add.cgi?' in path or '/del.cgi?' in path:
            text = ResponseText()
            text.feed(response.text)
            print(json.dumps({'phase': 'router_mutation_response', 'http_status': response.status_code,
                              'action': 'add' if '/add.cgi?' in path else 'delete',
                              'response_length': len(response.content),
                              'redirect_paths': [s.split('?')[0] for s in re.findall(r'[\'"](/[^\'"\s]*)[\'"]', response.text)][:8],
                              'is_firmware_error_page': '/resource/english/errdes.html' in response.text,
                              'error_scripts': [re.sub(r'[a-fA-F0-9]{32,}', '[REDACTED]', script)
                                                for script in re.findall(r'<script[^>]*>(.*?)</script>', response.text, re.S | re.I)
                                                if '/resource/english/errdes.html' in response.text and not re.search(r'cookie|token|password', script, re.I)],
                              'visible_text': ' '.join(' '.join(text.parts).split())[:1000]}), flush=True)
        return response


async def validate(mac):
    settings = Settings().model_copy(update={'writes_enabled': True, 'verified_writes': True})
    engine, sessions = database(settings.database_url)
    adapter = DiagnosticAdapter(settings)
    controller = Controller(settings, adapter, sessions)
    attempted = False
    restored = False
    baseline = set()
    try:
        observations, restrictions, _ = await adapter.snapshot()
        device = next((d for d in observations if d.mac == mac), None)
        if not device or not device.online:
            raise RuntimeError('Approved device must currently be online')
        with sessions() as db:
            stored = db.get(Device, mac)
            if not stored or stored.protected or controller.protected(device):
                raise RuntimeError('Approved device is unknown or protected')
        if any(r.mac == mac for r in restrictions):
            raise RuntimeError('Device already has a restriction; existing policy will not be changed')
        baseline = {(r.mac, r.domain, r.scope, r.ssid) for r in restrictions}
        print(json.dumps({'phase': 'preflight', 'mac': mac, 'hostname': device.hostname,
                          'ip': device.ip, 'port': device.port, 'protected': False}), flush=True)
        with sessions.begin() as db:
            controller.event(db, 'access_validation_started', mac, 'Owner authorized brief block and unblock')
        attempted = True
        try:
            await controller.manage(mac, True)
            observations, restrictions, _ = await adapter.snapshot()
            target = [r for r in restrictions if r.mac == mac]
            if not target:
                raise RuntimeError('Block rule not confirmed')
            print(json.dumps({'phase': 'block_verified', 'rules': len(target),
                              'scope': target[0].scope, 'ssid': target[0].ssid}), flush=True)
            await asyncio.sleep(10)
            observations, _, _ = await adapter.snapshot()
            current = next((d for d in observations if d.mac == mac), None)
            print(json.dumps({'phase': 'association_after_block',
                              'router_reports_online': current.online if current else None}), flush=True)
        finally:
            await controller.manage(mac, False)
            _, restrictions, _ = await adapter.snapshot()
            actual = {(r.mac, r.domain, r.scope, r.ssid) for r in restrictions}
            if actual != baseline:
                raise RuntimeError('Access-control state differs from the pre-test baseline')
            restored = True
            print(json.dumps({'phase': 'unblock_verified', 'baseline_restored': True}), flush=True)
        with sessions.begin() as db:
            controller.event(db, 'access_validation_completed', mac, 'Live block/unblock readback passed; baseline restored')
        print(json.dumps({'phase': 'complete', 'success': True}), flush=True)
    finally:
        if attempted and not restored:
            Path('/data/access-validation-rollback.json').write_text(json.dumps({'mac': mac, 'action': 'unblock', 'reason': 'Restoration not verified'}))
            print(json.dumps({'phase': 'restoration_unverified', 'mac': mac}), flush=True)
        await adapter.close()
        await controller.adguard.close()
        engine.dispose()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--mac', required=True)
    args = parser.parse_args()
    try:
        asyncio.run(validate(normalize_mac(args.mac)))
    except Exception as error:
        print(json.dumps({'phase': 'failed', 'error': type(error).__name__, 'detail': str(error)}), flush=True)
        raise SystemExit(1)
