# Validation record — 19 October 2026

## Executed successfully

- Router login in the local in-app browser with owner-supplied credentials.
- Actual firmware/hardware inspection and read-only device table inspection.
- Inspection of served login, session, device, status, wired/WLAN blacklist and form-helper source.
- Empty-cookie response verified as HTTP 200 “Waiting...” redirect HTML; adapter renewal detection and regression tests cover it.
- Server SSH inspection, router reachability, Docker/Compose, networks, occupied ports, resources and existing Tailscale configuration.
- Deployed isolated non-root backend, migrations, persistent volume, bounded resources/logs and loopback publication.
- Authenticated API access to actual router data: 16 known identities, initially 12 online (later 11); three critical protected identities; zero blacklist entries at inspection.
- Unauthenticated API requests return 401.
- Tailscale private HTTPS `/health` and authenticated dashboard verified from Windows; certificate validation remained enabled.
- Owner enabled account HTTPS and ran the privileged Serve command. Existing services/volumes and firewall/routing settings were preserved.
- Backend automated tests: **15 passed** after AdGuard integration; **17 passed locally** after the live-write fix and native form-order regression tests. Includes migration-to-model comparison, authentication latch, real-shaped session-expiration renewal, parsing/deduplication, policy guards, block/unblock workflow, readback failure, critical-device guards, monitoring debounce/outage/recovery, database persistence, schedule recovery/manual pause/timezone, notification outbox and AdGuard aggregate privacy/read-only/authentication/cache/error behavior. Mock/sanitized fixtures only; these are not evidence of live router mutation.
- Android unit tests: **8 passed**, including repository cache persistence/failure, ViewModel authentication/error/management state, list filters/sort/search and workflow messages.
- Debug APK and instrumented-test APK built successfully with JDK 21 / SDK 36 / Gradle 8.13.
- Android lint after redesign/AdGuard integration: **0 errors**, 29 warnings, principally newer dependency and KTX/kapt recommendations. Explicit backup/device-transfer exclusions are configured. Report retained under `android/app/build/reports`.
- An Android test phone on Android 16: both APKs installed. USB proved unreliable; wireless ADB subsequently connected.
- **3 instrumented tests passed on the phone after redesign/AdGuard integration**: live authenticated HTTPS/backend/router data, AdGuard service/filtering/DNS/blocking and protected-device checks; native navigation across Devices/Blocked/Activity/Settings; welcome setup and blocked empty-state UI checks. An earlier attempt got 502 during backend recreation and the USB connection dropped; the later stable wireless runs passed all tests.
- App configured privately on the phone: API token encrypted through Android Keystore; one-time private provisioning file deleted by the test. No token in APK source, test arguments, or printed logs.
- SQLite online backup command executed successfully, including integrity check; files written in the application volume at `/data/backups/`.
- Dedicated restart test compared SHA-256 of persisted device identities, first-seen timestamps, aliases, trust and protection before/after restart: matched. Health and live router recovery passed afterward.
- Existing AdGuard Home verified through authenticated API and actual DNS lookups from the backend container: protection/filtering enabled, normal resolution successful and test ad domain blocked. No AdGuard configuration changes. See `adguard.md` for scope and aggregated statistics.
- Signature teal UI redesign installed on the real phone. Default dark/light palettes, compact dashboard, device badges/rows, activity styling, network illustration and AdGuard card were visually inspected. Optional wallpaper-based dynamic colors remain supported. Screenshot: development workspace `artifacts/phone-dashboard-redesign.png` (excluded from Git).

## Validation boundaries and remaining checks

- **Wi-Fi block/unblock validation passed** with explicit owner approval for one test phone. The router confirmed the rule, reported the phone offline during the ten-second block, and confirmed removal and exact restoration of its blacklist baseline. Initial HTTP-200 error responses were rejected by readback verification; native field ordering with CSRF last fixed the request. Production writes and scheduling are enabled after that validation; protected infrastructure identities remain excluded. Wired blocking has not been live-tested.
- No WAN/Wi-Fi/DHCP/DNS/provisioning/filter-policy changes, router reboot/reset or firmware update were performed.
- Notification worker delivery while the phone is sleeping/Dozing has not been exercised over an extended period. Android may delay periodic work. Immediate push infrastructure is not deployed; optional ntfy relay has automated tests only.
- Scheduling is enabled after the live Wi-Fi validation, but no real scheduled router command has been executed as a test. Simulated scheduling/recovery tests passed.
- Remote use of this phone from a separate mobile-data network has not been tested; phone HTTPS connection was tested on the home network with Tailscale.
- No reliable router uptime, internet reachability, bandwidth control or per-device traffic signal was verified.
- This is a first tested sideload build, not a claim of long-duration production burn-in. Debug signing is used.

Private phone setup values belong in a local, git-ignored file with restricted permissions; never commit them. Source stays in the workspace, while `.env` belongs only on the server. Backup retention automation is documented but not installed.

## Minimal interface follow-up

At the owner's request, removed Activity from bottom navigation. Navigation now contains Overview, Devices, Blocked and Settings. Activity remains accessible under Settings → Network activity, with back navigation verified. Simplified the overview to connected-device status and an AdGuard protection summary; removed its activity feed, extra counters, network illustration and router technical details. Simplified device rows and grouped Settings into collapsed sections. AdGuard statistics remain available under DNS protection; router totals and diagnostics remain under Diagnostics. APKs rebuilt and installed on the test phone through wireless debugging. All three instrumented tests passed, including the changed activity navigation and live router/AdGuard connectivity checks.

## Approved live access test

The owner selected the test phone by MAC, not its alias. The first preflight found it offline and made no change. Later online preflights allowed debugging of the rejected form request. All failed attempts verified an unchanged blacklist baseline. The successful attempt added one wireless rule for SSID-1, observed router association become offline, waited ten seconds, removed that rule, and verified the entire pre-test restriction set was restored. The ordinary backend was stopped during each one-off validation session and restarted in a host-side finally block, preventing monitoring/session races. API health, discovery, zero remaining restrictions, enabled write flags and protected-device count were checked after deployment.

The updated backend suite passed all 17 tests both locally and in the deployed Python 3.12 container. A final API check confirmed the test MAC online again on SSID1, unrestricted, with writes enabled and zero blocked devices. The revised Android instrumentation test compiles and includes a read-only check that the test device's block button becomes enabled; that final added check could not be executed because wireless ADB disconnected. Earlier three-test on-phone navigation/connectivity runs remain valid as recorded above. Existing installed UI reads the server write flag during refresh; no APK change is needed for enablement.


## Router access release — 20 October 2026

Added Settings → Router access, with confirmed release and explicit resume. All **22 backend tests** and **9 Android unit tests** passed; both APKs built. Updated APKs installed on the test phone over wireless ADB, and all **3 instrumented tests** passed, including the new release confirmation/cancel UI, native navigation, live router and AdGuard checks.

A live end-to-end test verified server logout, successful authentication from a separate Windows client, that client's verified logout, durable pause after a real backend container restart, and manual sync remaining paused. AdGuard stayed online while released. Explicit resume restored fresh router data. Normal monitoring was restored at the end of testing. No block/unblock or network configuration changes were made for this feature.
