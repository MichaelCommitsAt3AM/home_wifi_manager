# Connection health and server speed test

Open Connection health from the overview or Settings. The backend observes three separate paths about every 30 seconds: unauthenticated HTTP reachability of the router dashboard, verified TLS connections to Cloudflare and Google public endpoints using fixed IP addresses, and an actual example.com DNS lookup through the configured AdGuard resolver (system DNS if AdGuard is not configured).

Internet reachability succeeds if at least one external TLS endpoint responds with a valid certificate. This checks the server's forwarding path without depending on DNS. It does not establish that every website is working, or that the phone has Internet access. Router-dashboard failure alone does not prove the router has lost power. Raw checks remain visible independently of the overall classification.

Two consecutive matching observations confirm a state transition. Outage timing starts at the first matching observation, so timing is approximate. Up to 100 episodes within the configured retention period are persisted in SQLite's Config table, along with the latest health observation. No schema migration is needed. Data older than 90 seconds is marked stale. After a monitoring gap longer than 90 seconds, an open episode ends at the last observation with a monitoring-gap label; the unobserved interval is not counted as verified downtime. History starts with this feature's deployment.

These checks do not authenticate to the router and continue when dashboard access is released. They do not restart the router, change DNS or alter network configuration.

## On-demand speed test

Run speed test asks the home server to make a bounded single-connection throughput measurement using [Cloudflare's published speed-test endpoints](https://github.com/cloudflare/speedtest/blob/main/README.md). It downloads 25 MB and uploads 5 MB, plus protocol overhead. Three small HTTP requests supply a median HTTP latency estimate. This is an estimate from the server through its Ethernet/Wi-Fi connection and ISP path; it does not measure the phone's Wi-Fi speed or record total household data usage. Other traffic, the server's resources and the selected endpoint affect results.

The owner confirms data usage in the app. An authenticated background task permits one test at a time and at most one start per minute. It has a 70-second overall deadline, fixed HTTPS destinations, certificate verification and no automatic retries. The latest result persists across app/server restarts. An interrupted test is marked interrupted, not completed or left permanently running. Public IP addresses and raw provider response bodies are not stored or returned.
