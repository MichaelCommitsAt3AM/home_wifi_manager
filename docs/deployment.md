# Deployment

This guide assumes a small Linux host with Docker and Compose installed. The example paths use `/opt/home-wifi-manager/backend` and the Compose project name `home-wifi-manager`; adjust them to match your own layout.

The backend publishes only `127.0.0.1:18765` and should be reached through a TLS-terminating reverse proxy or a private overlay network. For example, Tailscale Serve can expose it on your tailnet:

```sh
sudo tailscale serve --bg --https=8443 http://127.0.0.1:18765
```

The resulting endpoint looks like `https://<host>.<tailnet>.ts.net:8443/`. Android must use a hostname that matches the certificate; avoid insecure plain-HTTP fallbacks. If the server cannot resolve its own tailnet name, validate with curl `--resolve` while preserving SNI and certificate checks.

## Start or upgrade only this service

```sh
cd /opt/home-wifi-manager/backend
docker compose up -d --build
docker compose ps
docker compose logs --tail=50 backend
python3 ../scripts/verify_server.py
```


Alembic applies migrations before serving. The volume survives recreate/restart. Run exactly one backend process/replica. Before upgrades, take a backup and retain the previous source/image. Do not use `down -v`, prune volumes, or modify another application's Compose project. To stop only this backend use `docker compose stop backend`; Serve can remain configured.

## Credentials and phone setup

`.env` should have mode 0600, with a random API token of at least 32 characters and your own router credentials. To retrieve the token privately, run on the server:

```sh
cd /opt/home-wifi-manager/backend
sed -n 's/^API_TOKEN=//p' .env
```

Do not publish the output. Install the debug APK via your phone's package installer or `adb install -r app-debug.apk`. Connect the phone to the same private network, open Home WiFi → Settings, paste your backend HTTPS endpoint and token, and choose Save & test. No router password is entered on Android. Background notifications require Android permission and use delayed periodic checks. Debug signing is intended for sideloading; preserve the signing key when issuing updates, or create your own release key before distributing the app.

## Backup and recovery

`scripts/backup.py` uses SQLite's online backup API, including WAL contents, then integrity-checks the copy. Copy it once to the project directory on the server and run:

```sh
cd /opt/home-wifi-manager/backend
docker compose exec -T backend python - < ../scripts/backup.py
```

It writes `/data/backups/router-<UTC>.db` with mode 0600. Copy important backups off-host into encrypted storage; this in-volume backup alone does not protect against disk loss. Keep e.g. seven daily and four weekly copies. This project supplies the backup command but has not installed an unattended host timer. Avoid unbounded accumulation. `.env` needs a separate encrypted backup.

To restore: stop only this backend, save the current volume as a rollback backup, use a one-off container mounting this same application volume to replace `router.db` from a known-good backup, remove only this database's stale WAL/SHM sidecars while no process has it open, ensure UID 10001 owns the restored files, then start the backend and run verification. Never restore over an open database or remove unrelated volumes. Schema downgrade is not automatic; restore the matching application version and backup together.

## Optional immediate notifications

Set `NTFY_URL` to an authenticated private HTTPS topic and `NTFY_TOKEN` to its publish token, then recreate only this backend. Review category preferences in the app. No public topic is used by default. The built-in phone worker is delayed and can be suspended by Android. A self-hosted ntfy Android client with an approved push/distributor setup is needed for an immediate background-notification service; this has not been deployed here.
