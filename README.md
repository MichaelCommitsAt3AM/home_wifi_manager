# Home WiFi Manager

Self-hosted Wi-Fi management for a home network: a native Android app backed by a FastAPI service that talks to the router, keeps historical metadata, runs access schedules, and reports on an existing AdGuard Home instance.

The router adapter targets the Huawei HG8546M and is based on reverse-engineering one device family. It is a household-specific integration rather than a general-purpose router API; other models or firmware versions may differ and require an adapter.

## Features

- Live device inventory from the router, with aliases, search, sorting and per-device detail/history views.
- Block and unblock devices, including protection flags for critical or trusted devices.
- One-time and weekly schedules, with timezone-aware execution, missed-run handling and idempotent desired-state changes.
- Event history and an offline cache of the last known device list on the phone, clearly marked when stale.
- Delayed background notifications through Android WorkManager, with per-category preferences.
- Optional ntfy outbox on the backend for near-immediate delivery when an external relay is configured.
- AdGuard Home monitoring plus confirmed timed protection pauses, resume, filter refresh and domain exceptions.
- Independent connection health and persisted outage history, with an on-demand server speed test.
- Timed device blocks with durable automatic unblock and recovery after server/router outages.
- Temporary "release router access" mode that pauses router writes so a technician can use the router dashboard.

The server is the only component that holds router credentials and owns the serialized router session. The phone stores the backend token encrypted with the Android Keystore.

## Architecture

```
Android app (Kotlin/Compose)  ──HTTPS + bearer token──▶  FastAPI backend  ──▶  Router
                                                          │
                                                          ├─▶  SQLite (history, schedules, events)
                                                          └─▶  AdGuard Home (monitoring + controls)
```

The backend is designed for exactly one process: SQLite, the scheduler and router session coordination all assume a single worker. Run it behind a TLS-terminating reverse proxy or a private overlay network such as Tailscale; do not expose it directly to the internet. See [architecture](docs/architecture.md) for design details and [security](docs/security.md) for the threat model.

## Repository layout

| Path | Contents |
| --- | --- |
| `backend/` | FastAPI app, SQLAlchemy models, Alembic migrations, router adapters, tests, Docker files |
| `android/` | Kotlin/Compose app (Hilt, Retrofit, Room, WorkManager) and its unit/instrumented tests |
| `docs/` | Architecture, API, deployment, security, AdGuard, router access and troubleshooting notes |
| `scripts/` | Operational helpers: server verification, persistence checks, router access validation and SQLite backup |
| `artifacts/` | Reference material such as UI screenshots and the AdGuard OpenAPI description |

## Setup: backend and Android app

These steps assume the source files are already on a Linux home server, Docker Engine with the Compose plugin is installed, and the server can reach the router. Use your own paths and credentials throughout. The current adapter targets the Huawei HG8546M running firmware **V3R017C10S125** at `http://192.168.100.1`; other firmware needs separate validation and potentially adapter changes.

### 1. Configure the backend

Open the `backend/` directory inside your server checkout. For example:

```bash
cd /path/to/home_wifi_manager/backend
docker --version
docker compose version
umask 077
test -f .env || cp .env.example .env
chmod 600 .env
nano .env
```

The copy command preserves an existing `.env`. Generate a new API token if one has not already been configured:

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

Set the following values in `.env`, replacing every `<placeholder>` with your own value:

```dotenv
API_TOKEN=<generated-api-token>
ROUTER_URL=http://192.168.100.1
ROUTER_USERNAME=<router-username>
ROUTER_PASSWORD='<router-password>'
WRITES_ENABLED=false
VERIFIED_WRITES=false
PROTECTED_MACS=<server-mac>,<management-computer-mac>,<management-phone-mac>
PROTECTED_IPS=<server-ip>,<management-computer-ip>,<management-phone-ip>
POLL_SECONDS=60
OFFLINE_POLLS=2
RETENTION_DAYS=90
ADGUARD_URL=
ADGUARD_USERNAME=
ADGUARD_PASSWORD=
ADGUARD_DNS_HOST=host.docker.internal
NTFY_URL=
NTFY_TOKEN=
```

Protected addresses prevent accidental blocking of administration devices. Replace the sample addresses from `.env.example` with your own; keep protected IP addresses reserved in DHCP and account for private/randomized phone MAC addresses.

AdGuard Home is optional. Leave the three AdGuard URL/credential values empty to disable the integration; the app hides its Overview card and DNS protection settings. Router monitoring, connection health and access controls work independently.

To enable an existing AdGuard Home installation, set `ADGUARD_URL`, `ADGUARD_USERNAME` and `ADGUARD_PASSWORD`. For an administration API on this server's port `3000`, use `ADGUARD_URL=http://host.docker.internal:3000`; the DNS probe uses port `53`. Use the actual reachable address and administration port for your installation. `ADGUARD_DNS_HOST` takes only a hostname or IP, without a URL scheme or port. A configured integration stays visible if AdGuard goes offline or requires authentication. Connecting the integration does not change clients' DNS settings; see [AdGuard integration](docs/adguard.md).

Keep credentials in the server's `.env`, outside version control. The phone needs only the backend URL and API token.

### 2. Start and verify the backend

Log out of the router's browser dashboard before starting: the router limits simultaneous dashboard sessions.

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=50 backend
curl http://127.0.0.1:18765/health
```

Database migrations run automatically. Allow about a minute for the initial router sync, then run the verification helper from `backend/`:

```bash
python3 ../scripts/verify_server.py
```

It checks health, authenticated device/settings access, router status and rejection of unauthenticated access. Keep exactly one backend process/replica. Data persists in the Compose volume across container recreations; do not use `docker compose down -v` when upgrading.

### 3. Make the backend reachable over HTTPS

Compose binds the backend to `127.0.0.1:18765`. Configure a TLS reverse proxy or private overlay network before connecting Android. With Tailscale installed and authenticated on the server, one option is:

```bash
sudo tailscale serve --bg --https=8443 http://127.0.0.1:18765
sudo tailscale serve status
```

Complete any HTTPS enablement prompt from Tailscale. Use the actual HTTPS address printed by Serve, which has this form:

```text
https://<server-name>.<tailnet-name>.ts.net:8443/
```

Connect the phone to the same tailnet and ensure its access rules permit the connection. With this configuration, Tailscale must be connected on the phone even while it is on home Wi-Fi. No public port forwarding is needed.

### 4. Connect the Android app

If the app is already installed, keep it and proceed directly to connection settings. Otherwise, download the signed APK from [the latest GitHub release](https://github.com/MichaelCommitsAt3AM/home_wifi_manager/releases/latest) and install it on Android 8 or newer, granting installation permission to the browser or file manager when prompted.

1. Open **Settings → Advanced settings → Connection**.
2. Enter the backend's HTTPS address and the same `API_TOKEN` configured in `.env`.
3. Tap **Save & test**.
4. Confirm that Overview loads devices, AdGuard status (when configured), and connection health. Outage history starts accumulating after the backend begins monitoring.
5. Grant notification permission if you want background notifications; Android may delay background checks.

The router and AdGuard passwords are never entered on the phone. App updates are available through the in-app update banner; manual update checks are under **Settings → Advanced settings → Diagnostics**.

### 5. Enable router changes after validation

The initial configuration supports monitoring while blocking and other router changes remain disabled. Keep both write flags disabled until controlled block/unblock validation has passed on a noncritical device for your router and firmware. See [validation](docs/validation.md) and the provided [router validation helper](scripts/validate_router_access.py); do not treat successful monitoring as proof that writes work.

After validation, set these values in `.env`:

```dotenv
WRITES_ENABLED=true
VERIFIED_WRITES=true
```

Apply changed environment values by recreating the service:

```bash
docker compose up -d backend
```

A plain container restart does not reload `.env`. The backend continues monitoring and executing schedules when the phone app is closed. For backups, upgrades, recovery and optional ntfy setup, follow [deployment](docs/deployment.md).

## Backend development

Requires Python 3.13.

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r backend\requirements.txt
cd backend
..\.venv\Scripts\python -m pytest -q
```

To run locally, copy `backend/.env.example` to `backend/.env`, set a strong `API_TOKEN` and real router credentials, then:

```powershell
cd backend
..\.venv\Scripts\alembic upgrade head
..\.venv\Scripts\uvicorn app.main:create_app --factory --host 127.0.0.1 --port 18765
```

Notes:

- `WRITES_ENABLED` and `VERIFIED_WRITES` gate router-modifying operations. Leave them disabled until block/unblock behavior has been confirmed on your hardware.
- `PROTECTED_MACS` and `PROTECTED_IPS` keep critical devices from being blocked by accident.
- Tests use explicit mock fixtures only; production never selects the mock adapter.
- Keep one backend worker.

## Android development

Requires JDK 21 and Android SDK platform 36.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Instrumented tests are separate from compilation and need a connected device or emulator.

## Deployment

Docker and Compose files are in `backend/`; Compose publishes the service only on loopback, and Alembic runs migrations before serving. Take a backup before upgrades, run exactly one replica, and terminate TLS in front of the service. Detailed steps, backup/restore procedures and optional ntfy configuration are in [deployment](docs/deployment.md).

Use **Settings → Advanced settings → Router access → Release router access** when a technician needs the router dashboard; the backend logs out of the router and pauses writes until **Resume router access** is selected. See [router access](docs/router-access.md).

## Documentation

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Deployment](docs/deployment.md)
- [Security](docs/security.md)
- [AdGuard integration](docs/adguard.md)
- [Connection health and speed test](docs/connection-health.md)
- [Timed blocks](docs/timed-blocks.md)
- [Router access](docs/router-access.md)
- [Router investigation](docs/router-investigation.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Validation](docs/validation.md)

Check [validation](docs/validation.md) for the current state of builds and live testing before relying on any capability against your own hardware.


## Android releases

The Android APK is built and signed by GitHub Actions. Pushes and pull requests run build, unit-test and lint checks; version tags publish a signed APK, SHA256SUMS and updater metadata. See [release and signing instructions](docs/android-releases.md).

The app checks GitHub Releases for compatible updates. Its update notice appears above the header and pushes the screen down. Debug builds offer **Preview update banner** at the top of Settings. Manual checks are under **Settings → Advanced settings → Diagnostics**. Downloads are checked for size, SHA-256, package/version and matching signing certificate before Android asks the user to install.
