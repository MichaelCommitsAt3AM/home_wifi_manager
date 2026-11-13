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
- Wi-Fi radio control: switch the 2.4 GHz mode (802.11b/g/n mixed, n only, and others) and channel from the phone, with a built-in channel analyzer that scans nearby networks and suggests the quietest channel.
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
VERIFIED_RADIO_WRITES=false
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

Compose binds the backend to `127.0.0.1:18765`, so the phone cannot reach it until you put HTTPS in front of it. **This was developed and tested with Tailscale** (`tailscale serve`), and that is the only configuration the project has been validated with. With Tailscale installed and authenticated on the server:

```bash
sudo tailscale serve --bg --https=8443 http://127.0.0.1:18765
sudo tailscale serve status
```

Complete any HTTPS enablement prompt from Tailscale (this turns on certificates for your tailnet). Use the HTTPS address printed by Serve, which has this form:

```text
https://<server-name>.<tailnet-name>.ts.net:8443/
```

Install Tailscale on the phone and sign in to the same tailnet. Tailscale must be connected on the phone even while it is on home Wi-Fi, because the address is a tailnet name. No public port forwarding is needed.

#### What the app accepts as a backend address

These rules come from the app's code (`Data.kt`, `AndroidManifest.xml`), so other setups can be judged without guessing:

| Requirement | Why |
| --- | --- |
| Must start with `https://` and include a host name or IP; a port and trailing `/` are fine. | The app rejects anything else, and Android cleartext traffic is disabled (`usesCleartextTraffic=false`). Plain `http://` addresses, including `http://192.168.x.x`, cannot work. |
| The server's certificate must be trusted by Android's built-in certificate authorities and match the host name in the address. | The app has no custom trust settings, so self-signed certificates, a private CA, or a certificate you installed on the phone are not accepted. The app reports an HTTPS certificate error. |
| The address must serve the API directly, without redirecting to another URL. | The HTTP client does not follow redirects. |
| No username, query string or fragment in the address. | Rejected by validation. |
| The API token must be at least 32 characters. | Rejected by validation. |
| The phone must be able to reach the address from wherever you use the app. | For Tailscale, the phone must be connected to the tailnet. |

Other setups that satisfy these rules should work but are **untested**: for example a reverse proxy such as Caddy or nginx with a real certificate for a domain you own (use a DNS challenge if the server is only reachable on your LAN), or a Cloudflare Tunnel. Do not expose the backend directly to the public internet: the API token is the only barrier in front of a service that controls your router. A path prefix (for example `https://host/wifi/`) only works if your proxy strips the prefix, because the backend serves at the root.

### 4. Install and connect the Android app

Android 8.0 or newer is required.

1. Open [the latest GitHub release](https://github.com/MichaelCommitsAt3AM/home_wifi_manager/releases/latest) on the phone and download the `.apk` asset. Optionally compare its SHA-256 with `SHA256SUMS` from the same release.
2. Open the downloaded file. When Android asks, allow your browser or file manager to install unknown apps, then install. This is a direct download (sideload), not a Play Store app.
3. Open the app. On first launch, **Settings** shows a *Connect to your home server* card. (Later, the same fields are under **Settings → Advanced → Server connection**.)
4. Enter the backend's HTTPS address (see the table above) and the same `API_TOKEN` configured in `.env`, then tap **Save and test**.
5. Confirm that Overview loads devices and connection health (and AdGuard status, if configured). Outage history starts accumulating after the backend begins monitoring.
6. Optionally allow notifications; Android may delay background checks.

Router and AdGuard passwords are never entered on the phone, and the token is stored encrypted with the Android Keystore. App updates arrive through the in-app update banner; manual checks are under **Settings → Advanced → Diagnostics**. If the app says it cannot reach the backend, see [troubleshooting](docs/troubleshooting.md): the usual causes are Tailscale not connected on the phone, a certificate that does not match the address, or router access still released.

#### Wi-Fi radio (mode and channel)

**Settings → Wi-Fi radio** shows the router's current mode and channel. Changing them restarts the router's Wi-Fi, so every device (including your phone, if it is on that Wi-Fi) disconnects for about a minute; the app asks for confirmation and reports success only after the router confirms the new values.

- The **Channel analyzer** scans nearby 2.4 GHz networks from the phone and recommends the least crowded channel. Android requires the *location* permission for Wi-Fi scans; the app does not store or send your location. Android also limits scans to about four every two minutes.
- Changes are disabled by default on the server. Set `VERIFIED_RADIO_WRITES=true` (together with `WRITES_ENABLED=true`) in `.env` after you have tried one change on your router, then recreate the backend. Viewing settings and scanning work without it.
- Choosing "n only" disconnects older 802.11b/g-only devices.

### 5. Enable router changes after validation

The initial configuration supports monitoring while blocking and other router changes remain disabled. Keep both write flags disabled until controlled block/unblock validation has passed on a noncritical device for your router and firmware. See [validation](docs/validation.md) and the provided [router validation helper](scripts/validate_router_access.py); do not treat successful monitoring as proof that writes work.

After validation, set these values in `.env`:

```dotenv
WRITES_ENABLED=true
VERIFIED_WRITES=true
# Optional: allow changing Wi-Fi mode/channel from the app
VERIFIED_RADIO_WRITES=true
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

Requires JDK 21 (newer JDKs such as 25 are not supported by this Gradle version; set `JAVA_HOME`) and Android SDK platform 36.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Instrumented tests are separate from compilation and need a connected device or emulator.

To build a signed release APK locally, provide your keystore through the environment variables the Gradle `release` build reads (never commit them), then run `:app:assembleRelease`:

```powershell
$env:ANDROID_KEYSTORE_PATH = "C:\path\to\release.p12"
$env:ANDROID_KEYSTORE_PASSWORD = "<password>"
$env:ANDROID_KEY_ALIAS = "<alias>"
$env:ANDROID_KEY_PASSWORD = "<password>"
.\gradlew.bat :app:assembleRelease
```

The APK is written to `android/app/build/outputs/apk/release/app-release.apk`. A release signed with the same key as the installed app updates it in place and keeps its settings; a different key requires uninstalling first. Install over USB or wireless debugging with `adb install -r app-release.apk`.

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

Each release is published at [GitHub Releases](https://github.com/MichaelCommitsAt3AM/home_wifi_manager/releases) with the APK, `SHA256SUMS` and `update.json`. To publish a new version: bump `versionCode`/`versionName` in `android/app/build.gradle.kts`, add `docs/releases/<version>.md`, push to `main`, wait for checks, then push a `v<version>` tag. The backend is not shipped as a release artifact; deploy it from the repository as described above.

The Android APK is built and signed by GitHub Actions. Pushes and pull requests run build, unit-test and lint checks; version tags publish a signed APK, SHA256SUMS and updater metadata. See [release and signing instructions](docs/android-releases.md).

The app checks GitHub Releases for compatible updates. Its update notice appears above the header and pushes the screen down. Debug builds offer **Preview update banner** at the top of Settings. Manual checks are under **Settings → Advanced settings → Diagnostics**. Downloads are checked for size, SHA-256, package/version and matching signing certificate before Android asks the user to install.
