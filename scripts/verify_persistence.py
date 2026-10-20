"""Run from backend/ on the server. Restarts ONLY this Compose service."""
import subprocess
import time
import urllib.request

query = "import sqlite3,hashlib,json; db=sqlite3.connect('/data/router.db'); rows=db.execute('SELECT mac,first_seen,alias,trusted,protected FROM devices ORDER BY mac').fetchall(); print(hashlib.sha256(json.dumps(rows).encode()).hexdigest())"
def fingerprint():
    return subprocess.check_output(['docker','compose','exec','-T','backend','python','-c',query], text=True).strip()

before = fingerprint()
subprocess.run(['docker','compose','restart','backend'], check=True)
for _ in range(30):
    try:
        urllib.request.urlopen('http://127.0.0.1:18765/health', timeout=2).close()
        break
    except (OSError, TimeoutError):
        time.sleep(1)
else:
    raise RuntimeError('Backend did not become healthy after restart')
assert before == fingerprint(), 'Persisted device identities/metadata changed across restart'
print('Persistence across backend restart: verified')
