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

Settings → Connections → Personal link shows the percentage and current status
in one compact line, without a checklist. All eight setup checks remain
(10 / 25 / 40 / 55 / 70 / 85 / 95 / 100%), with elapsed time, attempt count
and a countdown to the next retry. These percentages
represent checks, not estimated time. Failures retry after 10–30 seconds while
the screen is observed, or a minute in the background while the process is alive.
Opening the screen or a later launch resumes with the same durable identity.
The local glucose/alert system does not wait for provisioning.

Android 910 uses `https://g-<12-random-base32-characters>.api-api-api.com/`.
The suffix has 60 random bits (not a sequential ID); a unique SQLite index and
collision retries under the writer lock guarantee that one live address never
selects two profiles. Owner credentials remain 256-bit and UUIDs do not change.
This is a deliberate URL-length/privacy trade-off for the small deployment:
it is harder to guess than a 4–6 character code, but has less entropy than the
old 128-bit viewer address. Possession of the URL grants read-only access.
It opens the application at the domain root: no `/viewer/` or `?profile=` is
needed in the shared address. Changing it can add an optional lowercase label,
e.g. `https://g-my-sugar-<12-random-characters>.api-api-api.com/`. The random suffix is
mandatory; a short, guessable alias alone must not protect medical records.
Changing the URL does not change the installation UUID, backend credential,
database or model. The old handle stops working, including existing SSE streams
(at the next update/heartbeat, at most 15 seconds). Already downloaded copies on
other devices cannot be remotely erased.

The migration replaces the existing long `gluco-*` DNS label once with a durable
short `g-*` label, as explicitly requested by the owner. There is exactly one DNS
address per profile: no alias table, redirect or fallback DNS name. Old DNS links
are rejected immediately and removed from the routing snapshot. UUID, owner
credential, handle, database and model stay unchanged. Explicit "Change link"
remains idempotent and revokes the prior address and query handle.

The server still returns `viewer_url`, `viewer_ready` and the legacy `viewer_path`.
Android 910 is required for short DNS addresses; older APKs must be updated.
PWA cache/session/manifest isolation recognizes both host prefixes so cached old
pages fail closed, never falling back to legacy data.
Starting with Android 909, the server's readiness hint is **not**
enough for 100%: the phone resolves DNS on its own network, validates the public
TLS endpoint and matching host, fetches the root PWA, its same-origin JS/CSS and
install manifest, then validates the read-only snapshot API. The owner endpoint
is queried again to confirm the URL did not change during those checks. Redirects
are rejected; TLS verification stays enabled; owner credentials are never sent
to public viewer requests. Network failures remain below 100% with a retry.
Copy/Open/Change only become available after this cycle succeeds. A new explicit
check never retains a stale 100%. Browser-specific negative DNS caches can still
lag behind the phone resolver: an old browser tab may need reloading later.
Link readiness and measurement freshness are separate: show the last sensor
timestamp (or no readings), never invent a reading or mark stale data as live.
Sync is already connected during this wait; automatic retries reuse the same
identity. New APKs adopt the domain without re-registering or
claiming history again. Local backends without a configured DNS zone retain
query links. Android currently accepts generated HTTPS viewer URLs only in the
explicit production `api-api-api.com` zone.

### Automatic DNS, without DNS credentials in the app

`personalDns.enabled` adds a restricted sidecar to the existing pod. The backend
atomically publishes **public DNS labels only** under
`/data/juggluco-installations/public-routing/viewer-domains.json`. The sidecar
mounts only that subdirectory, read-only: no health database, owner tokens,
provider credentials or Kubernetes token is exposed to the backend container.
A namespaced Role permits the sidecar to reconcile HTTPRoutes in `juggluco`.
Routes have exact hostnames (16 per route), not a wildcard that could capture
other applications. Only controller-labelled routes with its own name prefix
are updated/removed; the existing main API route is untouched.

The cluster's existing ExternalDNS (`gateway-httproute` source) creates real
per-profile Cloudflare DNS records. The existing `*.api-api-api.com` Gateway
certificate and tunnel serve HTTPS; no per-profile certificates or new DNS
provider keys are required. A probe verifies the **public HTTPS endpoint and
matching host identity** before reporting ready. Missing controller state is
pending, not success. DNS propagation may take several minutes. Rotated names
are rejected immediately at the backend even while a cached DNS record remains.

Deployment requirements: ExternalDNS must watch the configured public Gateway,
the namespace must be permitted by that Gateway, and the configured zone must
have wildcard HTTPS coverage. These prerequisites already exist in General-1.
The controller has no cluster-wide Role and cannot read Secrets; its projected
short-lived Kubernetes token is mounted only in the sidecar. Existing data
directories and database schemas remain compatible with earlier releases.

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
Domain viewers are additionally isolated by browser origin. The server resolves
the profile from the validated Host header and ignores `?profile` overrides.
All write/admin routes are blocked on viewer hosts, even with an owner token.
The root-scoped service worker caches only `/` and `/viewer/` navigation; API
requests remain NetworkOnly. Unknown or revoked hosts never show legacy data.

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
