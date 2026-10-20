"""Run inside the backend container; SQLite online backup includes WAL data."""
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

directory = Path(sys.argv[1] if len(sys.argv) > 1 else '/data/backups')
directory.mkdir(parents=True, exist_ok=True, mode=0o700)
target = directory / ('router-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '.db')
with sqlite3.connect('/data/router.db') as source, sqlite3.connect(target) as destination:
    source.backup(destination)
    assert destination.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
target.chmod(0o600)
print(target)
