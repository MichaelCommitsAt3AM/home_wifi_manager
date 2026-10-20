# AdGuard Home integration

This integration is optional and disabled by default in `.env.example`. Leave `ADGUARD_URL` empty to disable it. The app hides the AdGuard Overview card and DNS protection settings when the backend reports `configured: false`. A configured but unavailable instance remains visible so connection or authentication failures can be addressed. To enable it, set `ADGUARD_URL`, `ADGUARD_USERNAME`, `ADGUARD_PASSWORD` and the appropriate `ADGUARD_DNS_HOST`, then recreate the backend with `docker compose up -d backend`.

The backend integrates with an existing AdGuard Home instance with read-only monitoring and owner-confirmed controls. It was verified against a deployed `adguardhome` container running the v0.107.x API. In that setup, AdGuard was the LAN's primary DNS server with no secondary DNS. Those are deployment-specific facts; adjust the integration to your own network, and note that reading documentation is not a substitute for checking your live configuration.

Example live checks:

- Normal `example.com` A lookups against the resolver return real addresses.
- Test ad domains such as `doubleclick.net` resolve to `0.0.0.0` when blocking is active.
- The authenticated AdGuard API reports whether DNS is running, protection and filtering are enabled, and how many filter rules are loaded.
- Aggregated statistics (queries, blocked queries, blocked rate, average processing time, observed clients) depend on AdGuard's configured statistics window and change over time; they are not lifetime counters or necessarily “today”.

The backend reaches AdGuard through Docker's `host.docker.internal:host-gateway` mapping: dashboard API on port 3000, DNS on port 53. Credentials come from your backend's mode-0600 `.env` (`ADGUARD_USERNAME`, `ADGUARD_PASSWORD`). The Android phone never receives those credentials and does not connect to the dashboard API directly. The controls can pause/resume protection, refresh existing blocking lists and manage explicit domain exceptions. They do not restart AdGuard or edit DNS upstream/DHCP configuration.

Monitoring upstream calls: `GET /control/status`, `/control/filtering/status`, and `/control/stats`. These are documented in the [official API for the installed version](https://github.com/AdguardTeam/AdGuardHome/blob/v0.107.79/openapi/openapi.yaml). The integration exports aggregate counts only, not query logs, visited domains, client addresses, upstream addresses or filter URLs. API access is serialized and cached for 60 seconds. Incorrect credentials stop further login attempts until backend restart. API outage and DNS test results remain separate; partial success is not shown as a full healthy state.

App overview shows a compact AdGuard protection summary. Settings → DNS protection expands the blocked queries, total queries, blocked percentage, server DNS probe result, filter count/rules, average processing time, last checked time and scope explanation. The signature teal app palette is default; system/light/dark and optional Android wallpaper colors remain available.

`GET /api/adguard` is protected by the same backend bearer token and returns this sanitized status. The dashboard includes the cached summary. A DNS probe is an actual lookup against the configured server resolver, not an HTTP status check. No filter enforcement is inferred just because an API returns 200.

These checks verify the **server's DNS path and AdGuard service**, not every client’s effective DNS routing. DHCP integration is deployment-specific; manually configured DNS, Android Private DNS, VPN DNS or browser DoH may bypass it. DNS blocking also cannot remove all ads served from the same domains as wanted content. The integration does not enforce client-wide DNS settings.


## Controls added 20 October 2026

Under Settings → DNS protection, confirm a 5/15/60-minute protection pause, resume protection, refresh existing blocking lists, or allow a domain. Protection pauses use AdGuard's native `/control/protection` duration in milliseconds; auto-resume is owned by AdGuard rather than this backend. Protection changes are read back from `/control/status`. Refresh uses `/control/filtering/refresh` and validates its updated count.

Domain exceptions use strict domain validation, include subdomains, and apply to all clients using AdGuard. The backend reads and preserves the existing user_rules list, adds a recognizable app-owned comment and allow rule, and verifies the resulting list. Removing an exception removes only the adjacent comment/rule pair owned by this app; unrelated owner rules remain untouched. Existing external allow rules must be managed in AdGuard. The list is reread immediately before writing to detect external edits, but the upstream API has no atomic compare-and-swap; avoid editing custom rules simultaneously in another dashboard. Existing $important or other special policies can still affect a domain; a saved rule is not a guarantee for clients bypassing this resolver.

Only app-managed exception domains are returned through the exception API. Query logs and other custom rule contents remain private to AdGuard. Monitoring cache is invalidated after control requests. Ambiguous writes are not automatically retried. API credentials remain on the server.
