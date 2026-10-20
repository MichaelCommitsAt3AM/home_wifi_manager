# Troubleshooting

- **App cannot connect:** confirm Tailscale (or your chosen private network) is connected on the phone, verify the backend address configured in Settings, and check `/health`. A browser health response does not prove API authentication; use Save & test with the private token.
- **401:** token missing/incorrect/rotated; re-enter it. Router credentials and API token are different. Disconnect clears the app token/cache.
- **TLS error:** use the exact hostname; confirm Tailscale HTTPS certificate provisioning. Do not disable certificate checks or switch Android to HTTP.
- **502 through Serve:** verify the backend container is up and `curl http://127.0.0.1:18765/health` works on the server. Serve activation requires sudo on this host. View existing configuration with `tailscale serve status`.
- **Router unavailable:** check the server LAN path to 192.168.100.1, dashboard access, and sanitized backend logs. Monitoring backs off. Cached states are stale, not proof of current association. A whitelist/policy/firmware change intentionally fails closed.
- **Router authentication failed:** check owner credentials on the server and restart only this container after correcting them. Authentication retries are latched after rejection to protect the router from lockout. Do not guess passwords.
- **Block button disabled:** the safe test has not been completed, the device is protected, or current data is stale. Both server write flags must be enabled only after actual validation. Do not bypass infrastructure protection.
- **No uptime/traffic data:** these were not reliably available on the inspected firmware. No values are fabricated.
- **Device shown offline while lease exists:** leases and presence differ; this app uses router status, with two-poll offline debounce. Devices behind another router/bridge and randomized MACs may need separate interpretation.
- **Schedule missed:** events older than five minutes after server downtime are skipped and audited. Inspect status, timezone and weekday. Manual management pauses active schedules. Complex cross-zone overlaps should be avoided.
- **Notification delayed:** Android WorkManager has a minimum 15-minute periodic interval and can run later under Doze. Grant notification permission, enable the preference, and keep Tailscale available. The first worker run establishes its cursor and does not flood historical events. Immediate push relay/distributor setup is optional and not configured by default.
- **Disk growth:** events expire after 90 days by default. Device identity/alias records remain. WAL/checkpoint behavior and database free pages can keep physical files larger than live rows. Backups also need an owner-managed retention policy.
- **Android build:** JDK 21, Android SDK platform 36, Gradle 8.13. Run `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`. SDK location is machine-local and is not checked in. Instrumented tests require an emulator or connected phone.
