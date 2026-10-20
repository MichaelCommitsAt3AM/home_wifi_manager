# API

All `/api/` endpoints require `Authorization: Bearer <API_TOKEN>`. There is one owner/admin role. Tokens are high-entropy deployment secrets; there is no public registration or default API password. `/health` is public liveness/database readiness only, with no LAN identities. Interactive docs are disabled. Generate OpenAPI locally through `create_app().openapi()` if needed.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/health` | Liveness and database availability |
| GET | `/api/dashboard` | Counts, sync freshness, monitoring/router health, capabilities, ten recent events |
| GET | `/api/adguard` | Read-only AdGuard protection, aggregate statistics and actual server DNS probe results; cached 60 seconds |
| GET | `/api/connection` | Observed router/Internet/DNS health, bounded outage history and latest speed-test state |
| POST | `/api/connection/check` | Refresh independent connectivity checks, bounded to one per five seconds |
| POST | `/api/connection/speedtest` | `{ "confirmed": true }`; start bounded 30 MB server test, poll `/api/connection` for completion |
| POST | `/api/adguard/protection` | `{ "enabled": false, "minutes": 15, "confirmed": true }`; native timed pause, or `enabled:true` to resume |
| POST | `/api/adguard/filters/refresh` | `{ "confirmed": true }`; refresh existing blocking lists, return updated count |
| GET | `/api/adguard/exceptions` | Domain exceptions managed by this app |
| POST | `/api/adguard/exceptions` | `{ "domain": "example.com", "allowed": true, "confirmed": true }`; add/remove an app-owned exception |
| GET | `/api/devices` | All known devices, local metadata and router restriction scope |
| GET | `/api/devices/{mac}` | One known identity |
| PATCH | `/api/devices/{mac}` | `alias`, `trusted`, `protected`; local metadata only |
| POST | `/api/devices/{mac}/access` | `{ "blocked": true, "confirmed": true, "minutes": 15 }`; optional 1–1440-minute timed block, router readback verification required |
| GET | `/api/router/access` | Durable pause, verified logout and error status |
| POST | `/api/router/access/release` | `{ "confirmed": true }`; persist pause, log out and verify session expiry |
| POST | `/api/router/access/resume` | Explicit login attempt, skip missed schedules, resume monitoring |
| POST | `/api/sync` | Serialized router sync; returns saved dashboard without contacting router while access is released |
| GET | `/api/activity` | `address`, `kind`, `before`, `after`, `limit` (1–500); cursor paging |
| GET/PUT | `/api/settings` | Poll seconds (30–3600), notification category allowlist; read-only safety/capability diagnostics |
| GET/POST | `/api/schedules` | List/create persistent schedules; list optionally filters by `address` |
| DELETE | `/api/schedules/{id}` | Disable a schedule, retain audit history |

Event pagination is descending by ID normally and ascending when `after` is supplied, allowing safe backlog draining. Timestamps are ISO 8601 UTC. Device `online` means the router reported online, after offline debounce. `blocked` means a MAC blacklist rule exists; read `restriction_scope` for SSID/interface scope. It is not a global reachability guarantee. History starts when the controller starts; it cannot reconstruct previous days.

Recurring schedule body:

```json
{"mac":"02:00:00:00:00:02","action":"block","timezone":"Africa/Nairobi","local_time":"22:00","weekdays":[0,1,2,3,4,5,6]}
```

One-time schedules replace `local_time`/`weekdays` with `run_at` (aware future ISO 8601 timestamp). Weekdays use Monday=0. Writes/schedules return capability errors while validation flags are off. Invalid identity/time/config returns 422; protected devices return 403; conflicts/capability errors 409; router failures 502; bad tokens 401; rate limit 429 with Retry-After. Limits per observed client: 180 reads/minute, 20 mutations/minute. Reverse proxy traffic may share one observed source; rate limits intentionally remain conservative. Never blindly retry a management request after an ambiguous failure: refresh state first.


Timed unblocks are persisted before router mutation and retry when overdue. They cannot be cancelled directly; explicitly unblock the device instead. Ordinary schedule cancellation and missed-run handling are unchanged. Connectivity/speed results describe the home server's path, not every client. Speed-test responses expose idle/running/completed/failed/interrupted state; failed runs do not fabricate throughput.
