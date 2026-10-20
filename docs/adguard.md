# AdGuard Home integration

The backend integrates with an existing AdGuard Home instance in read-only mode. It was verified against a deployed `adguardhome` container running the v0.107.x API. In that setup, AdGuard was the LAN's primary DNS server with no secondary DNS. Those are deployment-specific facts; adjust the integration to your own network, and note that reading documentation is not a substitute for checking your live configuration.

Example live checks:

- Normal `example.com` A lookups against the resolver return real addresses.
- Test ad domains such as `doubleclick.net` resolve to `0.0.0.0` when blocking is active.
- The authenticated AdGuard API reports whether DNS is running, protection and filtering are enabled, and how many filter rules are loaded.
- Aggregated statistics (queries, blocked queries, blocked rate, average processing time, observed clients) depend on AdGuard's configured statistics window and change over time; they are not lifetime counters or necessarily “today”.

The backend reaches AdGuard through Docker's `host.docker.internal:host-gateway` mapping: dashboard API on port 3000, DNS on port 53. Credentials come from your backend's mode-0600 `.env` (`ADGUARD_USERNAME`, `ADGUARD_PASSWORD`). The Android phone never receives those credentials and does not connect to the dashboard API directly. The integration never restarts AdGuard, changes filters or edits DNS configuration.

Read-only upstream calls: `GET /control/status`, `/control/filtering/status`, and `/control/stats`. These are documented in the [official API for the installed version](https://github.com/AdguardTeam/AdGuardHome/blob/v0.107.79/openapi/openapi.yaml). The integration exports aggregate counts only, not query logs, visited domains, client addresses, upstream addresses or filter URLs. API access is serialized and cached for 60 seconds. Incorrect credentials stop further login attempts until backend restart. API outage and DNS test results remain separate; partial success is not shown as a full healthy state.

App overview shows a compact AdGuard protection summary. Settings → DNS protection expands the blocked queries, total queries, blocked percentage, server DNS probe result, filter count/rules, average processing time, last checked time and scope explanation. The signature teal app palette is default; system/light/dark and optional Android wallpaper colors remain available.

`GET /api/adguard` is protected by the same backend bearer token and returns this sanitized status. The dashboard includes the cached summary. A DNS probe is an actual lookup against the configured server resolver, not an HTTP status check. No filter enforcement is inferred just because an API returns 200.

These checks verify the **server's DNS path and AdGuard service**, not every client’s effective DNS routing. DHCP integration is deployment-specific; manually configured DNS, Android Private DNS, VPN DNS or browser DoH may bypass it. DNS blocking also cannot remove all ads served from the same domains as wanted content. The integration does not enforce client-wide DNS settings.
