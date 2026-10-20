# Router dashboard access

The HG8546M limits simultaneous dashboard logins. In the mobile app, open **Settings → Advanced settings → Router access → Release router access**, then confirm. The home server saves a durable pause before sending the firmware logout request. The app reports whether logout was verified; a failed request never resumes background login automatically.

While released, the app shows saved device observations. Router polling, block/unblock commands and schedule execution stop. AdGuard monitoring continues independently. Refreshing the app, restarting the backend or reopening the phone app does not reclaim the router session.

After the technician logs out of the dashboard, select **Resume router access**. This explicitly attempts authentication and refreshes router data. If login fails, access remains paused. Ordinary scheduled occurrences missed during the pause are skipped, not replayed; overdue timed unblocks are retained and retried after resume; recurring schedules continue at their next future occurrence. Existing router restrictions remain in force during the pause.

If logout cannot be confirmed, the app says so and offers Retry logout. If the backend has already restarted without its previous in-memory session, it cannot safely acquire a new session just to log out; an old router session may need to expire. Resume only after dashboard access is no longer needed.

The inspected firmware submits `POST /logout.cgi?RequestFile=html/logout.html`. The adapter then probes the device endpoint without its automatic authentication helper and requires the firmware's unauthenticated redirect response. It clears its cookies only after verified expiry. No router provisioning, DNS, Wi-Fi or filter policy is changed.
