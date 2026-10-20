"""Read-only deployment checks. Run on the server from backend/; never prints tokens or device identities."""
import json
import urllib.request
from pathlib import Path

config = dict(line.strip().split('=', 1) for line in Path('.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
base = 'http://127.0.0.1:18765'
headers = {'Authorization': 'Bearer ' + config['API_TOKEN']}
for path in ['/health', '/api/dashboard', '/api/devices', '/api/settings']:
    with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=60) as response:
        data = json.load(response)
    if path == '/api/dashboard':
        print(json.dumps({k: data[k] for k in ['router_online', 'last_sync', 'total', 'connected', 'blocked', 'error']}))
    elif path == '/api/devices':
        print('Device retrieval:', len(data), 'devices; protected:', sum(d['protected'] for d in data))
    elif path == '/api/settings':
        print('Router writes enabled:', data['writes_enabled'])
    else:
        print('Health:', data['status'])
try:
    urllib.request.urlopen(base + '/api/devices', timeout=5)
    raise AssertionError('Unauthenticated request unexpectedly allowed')
except urllib.error.HTTPError as error:
    assert error.code == 401
    print('Unauthenticated access rejected: 401')
