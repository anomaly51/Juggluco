# Personal Android connections

## User flow

A fresh phone installation provisions a stable installation UUID and a private
256-bit write credential on first launch. The credentials are atomically saved
in Android's `noBackupFilesDir` **before** contacting the server. Restarts, lost
responses and APK updates reuse that identity. Uninstalling/clearing app data
creates a new installation; this is not an account recovery system.
The local identity is committed in a small SQLite transaction. Explicit
Android 12+ extraction rules also exclude the backend credential preferences and
forecast cursors from device transfers. This follows Android's guidance on
[device-specific identifiers and backup exclusions](https://developer.android.com/identity/data/autobackup).

Settings → Connections → Personal link shows completed setup milestones
(1 / 25 / 65 / 85 / 100%), a retry action, the read-only URL and Copy/Open/Change.
These percentages represent steps, not estimated elapsed time. Network failures
retry after a minute while the application process is alive and on later launches.
The local glucose/alert system does not wait for provisioning.

The viewer URL is `/viewer/?profile=<128-bit-random-handle>`. Changing it can add
an optional lowercase label, e.g. `my-sugar-<random-handle>`. The random suffix is
mandatory; a short, guessable alias alone must not protect medical records.
Changing the URL does not change the installation UUID, backend credential,
database or model. The old handle stops working, including existing SSE streams
(at the next update/heartbeat, at most 15 seconds). Already downloaded copies on
other devices cannot be remotely erased.

## Isolation

The existing single-user routes and database are retained for compatibility.
New installations use `/devices/<uuid>/v1/...` with their own bearer credential.
Only a SHA-256 digest of this high-entropy secret is kept in the registry.
Owner authentication happens before opening a tenant runtime. The global legacy
API credential does **not** authenticate as any newly registered installation.

Each installation has its own SQLite DB, forecast service, model records,
activation metadata, intake/chat history, SSE hub and cursor-signing key. No
query needs to remember a tenant filter. The bounded runtime cache evicts idle
apps, closes their HTTP clients and database connections, and reloads the same
durable DB on demand. An active request/stream pins its runtime until completion.

With `JUGGLUCO_DATABASE_PATH=/data/juggluco.db`, storage is:

```
/data/juggluco.db                         # existing single-user history
/data/juggluco-installations/registry.sqlite3
/data/juggluco-installations/<uuid>/health.sqlite3
```

The chart's existing `/data` PVC covers all these files. Back up the **whole
directory**, including registry and WAL-safe SQLite snapshots, not just the old
database. Never put owner secrets, viewer capabilities, DB files or exports in Git.

`/v1/shares/<handle>/{session,snapshot,glucose,stream}` is a strict read-only
allowlist, not a general proxy. Unknown/malformed/revoked handles never fall back
to legacy data. Browser cookies and supplied bearer headers are discarded before
dispatch. HTTPS, trusted-host checks and no-store/no-referrer headers still apply.
Personal PWA manifests preserve the profile URL when installed; IndexedDB and
logout state are partitioned by that explicit URL. No ambient last-user shortcut
is used, including when offline.

## Existing phone/history

Upgrades with a configured backend are **not** silently reassigned to an empty
profile. Personal link offers “Connect existing history” with confirmation.
The authenticated, idempotent `/v1/installations/claim` operation binds the old
database and model to one installation; a second claimant is rejected. The phone
switches its endpoint only after the response is validated and saved, and never
while an ambiguous chat turn is pending. Old clients keep using the legacy API.

The existing public legacy viewer remains governed by `JUGGLUCO_VIEWER_PUBLIC`.
Rotating a personal URL does not revoke that separately configured legacy URL or
the legacy admin credential. Operators should disable the legacy public viewer
after migrating clients if they no longer want that separately public access.

## Forecasts

Every new reading uses that installation's own forecast service/history. A new
profile starts with the existing baseline and its existing safety/data-readiness
rules. It does **not** inherit the original owner's personal model and is not
promised an accurate forecast without sufficient data. Training/approval remains
explicit through the existing `scripts/forecast_admin.py --database <tenant-db>`
workflow. Existing deployment/bootstrap jobs still target the legacy database;
this change does not auto-promote unvalidated personalized models for new users.

## Deployment and operating limits

Deploy backend + rebuilt PWA **before** distributing the Android build. No Vault
secret needs to be embedded in the APK: installation credentials are generated
on the phone. Existing provider credentials remain server-side.

Configuration (also in the Helm chart):

| Variable | Default | Meaning |
| --- | --- | --- |
| `JUGGLUCO_PERSONAL_CONNECTIONS` | `true` | Enable new personal routes |
| `JUGGLUCO_CONNECTION_REGISTRATION_LIMIT` | `100` | New registrations per rolling 24 h, globally |
| `JUGGLUCO_CONNECTION_RUNTIME_LIMIT` | `32` | Concurrent loaded profiles; idle eviction |
| `JUGGLUCO_CONNECTION_AI_DAILY_LIMIT` | `100` | AI/chat POST requests per installation per UTC day |

AI limits do not throttle glucose ingestion. They are request-count limits, **not
a dollar spending cap**. Public self-registration needs provider-side spending
limits and deployment-level abuse/rate controls before broad public distribution.
Tests use synthetic data and do not make paid provider calls.

Keep the current single-replica/single-worker SQLite deployment. The process-local
SSE hubs are not multi-worker fan-out. This is an isolated multi-installation first
version, not an unbounded global-scale service; growing beyond the configured
capacity requires a shared DB/broker, storage quotas, account recovery and abuse
controls. This implementation does not alter alarm or insulin-dose logic.

Local verification:

```
cd backend
python -m pytest tests/test_connections.py tests/test_viewer_api.py tests/test_viewer_session.py tests/test_viewer_stream.py
cd ../pwa
pnpm test:run
pnpm build
```
