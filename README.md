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
                                                          └─▶  AdGuard Home (read-only)
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

## Phone setup

1. Install the APK (sideloading the debug build is fine for personal use; grant install permission only to the app you use to open it).
2. Connect the phone to the same private network as the backend, or to a VPN/overlay that can reach it.
3. Open **Home WiFi → Settings**, enter the backend HTTPS base URL, and paste the API token from the server's `.env`. No backend address is built into the app.
4. Choose **Save & test** to verify connectivity.
5. Review critical/trusted devices and grant notification permission if you want background activity checks.

The router password is never entered on the phone.

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
