# Juggluco intake backend

This service is the authoritative write path for the redesigned phone intake flow.
It stores confirmed meal/insulin events in SQLite and sends only meal-analysis inputs
to OpenRouter. The Android app must never contain an OpenRouter key.

The service can be reached either through USB `adb reverse` or directly from a phone
on the same trusted Wi-Fi subnet. It is not designed to be exposed to the public
internet.

## Important security note

If an API key has been pasted into a chat, issue, source file, shell history, or other
shared surface, revoke it and create a new limited key before running this backend.
Never reuse the exposed value. The repository contains no working secret and does not
create a `.env` file.

Meal and vision requests enforce Zero Data Retention routing and deny providers that
collect request data. Dedicated speech-to-text requests carry the same privacy
preferences, but should also use account-wide OpenRouter ZDR/guardrails because STT
provider-routing controls can differ from chat-completion controls. Images are decoded
and re-encoded before upload, removing EXIF and GPS metadata. Raw photos and audio are
never written to SQLite or retained by the application; the multipart runtime may use
a short-lived OS temporary file for a large upload before validation finishes. SQLite
still contains health-adjacent event data, so protect the Windows account and disk.

AI carbohydrate values are estimates. The app shows the range and warnings, records
the result immediately, and keeps an explicit Undo/correction path. This backend does
not calculate or recommend insulin doses; only an unambiguous dose already reported
by the user may be recorded automatically.

For a container image and a single-replica Kubernetes deployment with persistent
SQLite storage, see [`../deploy/kubernetes/README.md`](../deploy/kubernetes/README.md).

## Local setup (PowerShell)

Python 3.11 or newer is required; Python 3.13 is supported.

```powershell
cd C:\path\to\Juggluco\backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[test]"
Copy-Item .env.example .env
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Put the generated random value in `JUGGLUCO_API_TOKEN` in `.env`, then put a newly
created OpenRouter key in `OPENROUTER_API_KEY`. Keep `.env` local; it is ignored by Git.
The selected model IDs are configuration, not Android constants.

Build the installable React PWA once before starting the backend. It is then
served from the same origin at `/viewer/`:

```powershell
cd C:\path\to\Juggluco\pwa
pnpm install --frozen-lockfile
pnpm build
cd ..\backend
```

The production bundle, manifest, service worker, and icons are generated in
`pwa/dist`; Node.js is not needed by the running Python process.

Meal chat defaults to the economical vision-capable
[`qwen/qwen3-vl-8b-instruct`](https://openrouter.ai/qwen/qwen3-vl-8b-instruct),
selected from OpenRouter's official model catalog with image input, structured-output,
and ZDR routing requirements. It remains configurable with
`OPENROUTER_MEAL_CHAT_MODEL`; `google/gemini-2.5-flash-lite` is a reasonable manual
fallback. There is no silent fallback that could weaken privacy/provider constraints.
Voice transcription defaults to the latency-oriented
`openai/whisper-large-v3-turbo` model through OpenRouter's dedicated speech-to-text
endpoint. Set `OPENROUTER_AUDIO_LANGUAGE=ru` (or another ISO-639-1/BCP-47 tag)
when a deployment has a known voice language; leave it blank or set it to `auto`
to retain automatic detection. Russian transcription also sends a narrowly scoped
Groq vocabulary hint for NovoRapid and Tresiba without supplying or inferring dose
values. The legacy single-request vision endpoint uses the same Qwen vision default.

For USB-only access, start the backend on loopback:

```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8765
```

Connect the physical phone to that loopback port without opening a LAN listener:

```powershell
adb -s <PHONE_SERIAL> reverse tcp:8765 tcp:8765
adb -s <PHONE_SERIAL> reverse --list
```

The Android base URL is then `http://127.0.0.1:8765`. The emulator can use the same
`adb reverse` command by replacing the serial with `emulator-5554`. The Android client
must send `Authorization: Bearer <JUGGLUCO_API_TOKEN>` on every endpoint except health.

For a physical phone on the same trusted Wi-Fi network, use the LAN launcher:

```powershell
.\run_lan.ps1
```

It detects the computer's active LAN address, adds that address to the backend's host
allowlist, and listens on port 8765. Set the Android base URL to the address printed by
the script, for example `http://<LAN_IP>:8765`. If Windows Firewall prompts, allow
TCP 8765 only for the local/private subnet. No `adb reverse` rule is needed.

The phone and computer must remain on the same network. A DHCP address can change after
reconnecting or rebooting; use a DHCP reservation in the router for a stable address.
Private RFC1918 HTTP addresses are accepted by the Android development client, while
non-local backend URLs still require HTTPS. Never forward port 8765 from the router or
expose this development listener publicly. A remote deployment needs TLS, per-device
credentials, backups, migrations, edge rate limiting, and an explicit privacy/compliance
review.

## API contract

### Read-only PWA/viewer API

The viewer is private by default. Generate a second, unrelated credential for
companion viewer devices:

```powershell
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Put it in `JUGGLUCO_VIEWER_TOKEN`. It must contain 32–512 URL-safe ASCII
characters and must
not equal `JUGGLUCO_API_TOKEN`. Open the same-origin PWA at `/viewer/` and enter
this viewer token once. `POST /v1/viewer/session` exchanges it for a signed
`Secure`, `HttpOnly`, `SameSite=Strict` cookie and never returns or stores the
token in React, IndexedDB, Cache Storage, a URL, or source code. The default
session lifetime is 30 days (`JUGGLUCO_VIEWER_SESSION_DAYS`); a foreground
session check renews it, while rotating the viewer token revokes every old
browser session immediately. “Выйти и удалить данные” removes the cookie from
that browser and clears its local PWA data; rotate `JUGGLUCO_VIEWER_TOKEN` if a
copied session must be revoked before its expiry.

For an intentional link-only deployment, set `JUGGLUCO_VIEWER_PUBLIC=true`.
This is an explicit opt-in and defaults to `false`; any value other than exactly
`true` or `false` prevents startup. In public mode anyone who can reach the URL
can read current and historical glucose plus a sanitized forecast. Public
responses replace reading IDs with process-local opaque IDs, omit sensor identity,
forecast activity metadata and meals. It exposes only a separate minimized
insulin projection (time, units, rapid/long type, and display name) requested
for the graph; event IDs, client IDs, edit metadata, and meal fields remain
private. `/v1/viewer/intakes` returns 403.
`GET /v1/viewer/session` returns
`{"authenticated":true,"access_mode":"public","expires_at_ms":null}` without
creating a cookie, and the snapshot/glucose endpoints accept anonymous requests.
`POST /v1/viewer/session` returns HTTP 409 because no login is required;
`DELETE /v1/viewer/session` remains an idempotent same-origin operation that clears
an old cookie. Setting public mode never relaxes the separate admin Bearer check:
all ingestion, chat, create, edit, and delete routes still require
`JUGGLUCO_API_TOKEN`.

The viewer credential, session, and anonymous public access are deliberately
rejected by all existing
Android/admin routes, including glucose ingestion and meal/insulin create,
edit, and delete operations. Bearer viewer/admin credentials remain accepted
on the GET-only viewer routes for backwards-compatible diagnostics, but the
admin token must never be entered in the PWA.

Remote viewer access must use HTTPS with a valid certificate: `/viewer/*` and
`/v1/viewer/*` fail closed on plain HTTP except on `localhost`/loopback for
development. When TLS terminates at a reverse proxy, set
`JUGGLUCO_VIEWER_TRUSTED_PROXY_CIDRS` to that proxy's exact source IP/CIDR so
the application may accept its `X-Forwarded-Proto: https`; never configure a
public catch-all network. The local LAN launcher is for the Android writer on a trusted
private Wi-Fi network only; it does not make a LAN-hosted PWA installable. A public
deployment additionally needs per-device credential rotation, an allowlisted
host name, edge rate limiting, backups, and the privacy/compliance controls
appropriate for health data.

The unauthenticated session-exchange body is capped at 2 KiB (including
chunked requests), validation errors redact their input, and production TLS
gateways should additionally rate-limit failed session exchanges.

`GET /v1/viewer/snapshot` is the bounded dashboard bootstrap. Query parameters:

- `from_ms` / `to_ms`: inclusive graph window, defaulting to the last 24 hours;
  a request may cover at most 31 days and cannot end more than 10 minutes in the
  future;
- `glucose_limit`: 1-2500, default 1500 (enough for a typical one-minute 24-hour
  CGM graph);
- `event_limit`: 1-500, default 100.

`GET /v1/viewer/stream` is the same-origin live channel. It uses Server-Sent
Events with the same viewer cookie/public authorization as the snapshot, sends
an initial `ready` event, a `glucose` event immediately after every durable CGM
mutation, and a `heartbeat` every 15 seconds. Every payload contains
`stream_id`, the monotonic `revision`, and `server_time_ms`; glucose events also
contain `latest_reading_at_ms`. The stream never carries source reading IDs,
sensor identity, glucose values, credentials, or forecast details. The browser
uses it as an invalidation signal and reconciles through the bounded snapshot.
Subscriber queues retain only the newest revision, so a suspended client cannot
create an unbounded backlog. Browser session streams close at signed-cookie
expiry and reconnect authentication is checked normally.

The response has stable, explicit fields:

```json
{
  "api_version": "v1",
  "stream_id": "7a9f6ed8-73e6-47dd-9427-a07dbe75fdad",
  "glucose_revision": 42,
  "server_time_ms": 1787212800000,
  "from_ms": 1787126400000,
  "to_ms": 1787212800000,
  "target_range": {
    "low_mg_dl": 75.6,
    "high_mg_dl": 162.0,
    "low_mmol_l": 4.2,
    "high_mmol_l": 9.0
  },
  "current_glucose": {
    "reading_id": "cgm-1787212500000",
    "measured_at_ms": 1787212500000,
    "glucose_mg_dl": 116.0,
    "trend_mg_dl_min": -0.4,
    "sensor_id": null,
    "sensor_generation": "Libre",
    "quality": 0.96,
    "utc_offset_minutes": 180,
    "received_at_ms": 1787212505000,
    "age_ms": 300000,
    "is_stale": false
  },
  "glucose_history": [],
  "glucose_history_order": "oldest_first",
  "glucose_history_truncated": false,
  "intake_events": [],
  "intake_events_order": "oldest_first",
  "intake_events_truncated": false,
  "insulin_events": [
    {
      "occurred_at_ms": 1787212200000,
      "insulin_units": 5.0,
      "insulin_type": "rapid",
      "insulin_name": "NovoRapid"
    }
  ],
  "insulin_events_order": "oldest_first",
  "insulin_events_truncated": false,
  "forecast": {"status": "no_data", "points": []}
}
```

`current_glucose` is always the newest stored reading, independently of the
requested graph window. `age_ms` is measured against `server_time_ms`, and
`is_stale` becomes true after 15 minutes so a cached or delayed value is never
presented as live. History and timeline arrays are oldest-first for direct chart
rendering. When a limit is reached, the endpoint keeps the newest entries,
returns the corresponding `*_truncated=true`, and the client can load older
entries through the page endpoints. Intake entries expose only active
`meal`, `rapid`, or `long` events; deleted records and internal client/analysis
identifiers are not returned. The nested forecast uses the existing
`ForecastCurrentResponse` contract and remains a conditional visualization,
never a dose recommendation.

That full projection describes authenticated private mode. Public mode keeps
the same shape for PWA compatibility but returns an empty intake timeline,
removes forecast activities and sensor identity, replaces source reading IDs
with process-local opaque IDs, and retains only the minimized `insulin_events`
projection described above. Anyone with the public link can therefore see
those insulin doses and times.

Older history is available from:

- `GET /v1/viewer/glucose?cursor=&limit=&from_ms=&to_ms=`;
- `GET /v1/viewer/intakes?cursor=&limit=&from_ms=&to_ms=`.

The intake page endpoint is available only in authenticated private mode and
returns 403 while public mode is enabled.

Both return `items`, nullable `next_cursor`, `has_more`, and
`order="newest_first"`. `limit` is 1-500. The first request defaults to a
31-day window ending at server time. Rows with identical timestamps are ordered
by their stable ID, so pagination neither merges nor drops simultaneous meals,
rapid insulin, long insulin, or CGM samples. Treat `next_cursor` as opaque: it is
HMAC-signed and bound to the route and exact time window; tampering, using it on
the other endpoint, or supplying different window parameters returns `422`.

These are browsing cursors, not durable synchronization revisions. A corrected
CGM row keeps its source ID/time, and the active-only intake view omits
tombstones. The PWA stores one explicitly labelled last-good offline snapshot
in IndexedDB and periodically replaces its bounded rolling window with a fresh
snapshot rather than treating page cursors as change cursors. The service
worker caches only the application shell, never `/v1/*` responses. Android
incremental synchronization continues to use
`GET /v1/intakes?after_sync_version=...` unchanged.

`glucose_revision` is a durable change watermark, unlike the browsing cursors.
The snapshot reads it before querying glucose rows, so a concurrent commit can
only make the watermark conservatively older than the returned data, never newer.
`stream_id` is process-local; a changed value after restart tells the PWA to
replace rather than merge its in-memory snapshot. SSE is intentionally
process-local because the production SQLite deployment has one replica and one
Uvicorn worker. A future multi-replica database deployment must replace the hub
with a shared pub/sub transport.

### Health

`GET /v1/health` is intentionally unauthenticated and returns only generic readiness
flags, including whether a dedicated viewer credential is configured. It never
returns credentials, provider names, endpoints, or model IDs.

### Transcribe an editable voice draft

`POST /v1/transcriptions` uses `multipart/form-data` with one required `audio`
file field. AAC, FLAC, M4A/MP4, MP3, OGG, and WAV are accepted up to
`JUGGLUCO_MAX_AUDIO_BYTES`. The request requires the same backend Bearer token as
the other protected `/v1` routes. An optional `language` form field accepts `auto`,
an ISO-639-1 code, or a BCP-47 locale such as `ru-RU`; when omitted, the deployment
setting above is used. A successful response contains only provider-neutral editable
text:

```json
{"text": "I drank one glass of milk"}
```

This endpoint does not create a meal-chat message, analysis, intake event, or sync
change. It does not write the recording or transcript to SQLite.

### Analyze a meal

`POST /v1/analyze` uses `multipart/form-data`:

- `meal_text`: optional UTF-8 text, up to 4,000 characters;
- `photos`: repeat this file field zero, one, or two times; JPEG/PNG/WebP inputs are
  decoded and normalized by Pillow;
- `audio`: optional AAC, FLAC, M4A/MP4, MP3, OGG, or WAV recording.

At least one of these inputs is required. A voice recording is first transcribed via
OpenRouter's dedicated speech-to-text endpoint, then the transcript and photos are
analyzed by the configured vision model using a strict JSON schema. A successful
response is:

```json
{
  "analysis_id": "74a86527-56c5-4bcf-8a15-144fa8ba7abc",
  "meal_name": "Rice bowl",
  "meal_description": "Rice with chicken and vegetables",
  "estimated_carbs_g": 52.0,
  "carbs_low_g": 42.0,
  "carbs_high_g": 66.0,
  "confidence": 0.76,
  "items": [
    {"name": "Cooked rice", "portion_g": 180.0, "carbs_g": 50.0}
  ],
  "assumptions": ["The bowl contains about 180 g of cooked rice."],
  "warnings": ["AI carbohydrate estimate; confirm the food and portion before saving."],
  "transcription": ""
}
```

The analysis is a draft. It does not appear on the graph until the user confirms it and
the app creates an intake event.

### Refine a meal in chat

The meal chat is a separate, persistent, multi-turn draft flow. It never saves insulin
and does not calculate or discuss insulin doses.

Create or idempotently recover a session:

`POST /v1/meal-chat/sessions`

```json
{
  "client_event_id": "c98178ed-c5ac-41d0-b8d3-dae4fe6bdf26",
  "occurred_at_ms": 1785870000000
}
```

The UUID is reserved for that chat and its eventual meal event. Retrying the same UUID
and timestamp returns the same session; changing the timestamp returns HTTP 409.

Send a turn with `multipart/form-data` to
`POST /v1/meal-chat/sessions/{session_id}/messages`:

- `text`: optional text, up to 4,000 characters;
- `photos`: repeat the field for every food or label photo;
- `audio`: optional supported recording.

At least one input is required. The default server limit is 24 photos, additionally
bounded by the per-photo limit and a 32 MiB aggregate source/normalized-image limit.
All limits are configurable, and limit errors report the active value. Photos are
EXIF-stripped in memory; neither raw photos nor raw audio are written to SQLite.

```json
{
  "session_id": "72f68e46-3378-48e0-9f28-ec22acfe8fc0",
  "assistant_message": {
    "id": "43cb24ac-bf42-47ea-9408-cd7d31c78452",
    "role": "assistant",
    "text": "I found a rice bowl. Please confirm the portion.",
    "photo_count": 0,
    "had_audio": false,
    "created_at_ms": 1785870010000
  },
  "proposal": {
    "meal_name": "Rice bowl",
    "meal_description": "Rice with chicken and vegetables",
    "total_portion_g": 350.0,
    "items": [
      {"name": "Cooked rice", "portion_g": 180.0, "carbs_g": 50.0}
    ],
    "estimated_carbs_g": 52.0,
    "carbs_low_g": 42.0,
    "carbs_high_g": 66.0,
    "confidence": 0.76,
    "absorption_speed": 0.64,
    "absorption_peak_minutes": 78,
    "absorption_duration_minutes": 245,
    "absorption_confidence": 0.58,
    "warnings": ["AI carbohydrate estimate; confirm before saving."]
  },
  "ready_to_confirm": true
}
```

Recognizable food or drink immediately produces a best-effort proposal, using a typical
serving, a wider carbohydrate range, and a warning when the portion is missing. An
explicit carbohydrate quantity is also sufficient by itself and may produce a generic
meal with an unknown (`0`) total portion. Optional nutrition or absorption estimates may
be `null`; the assistant does not ask for them. `proposal` is `null` only when no meal,
drink, or carbohydrate record can reasonably be extracted. The user can send any number
of correction turns before saving, including after `ready_to_confirm=true`.
Every accepted assistant turn completely replaces the session's current proposal and
readiness state; an older ready proposal cannot be confirmed after a newer not-ready
correction. `GET /v1/meal-chat/sessions/{session_id}` returns the persistent
text/transcription history, media-presence counts, latest proposal, and confirmation
state. It never returns raw media.

The time remains editable while the session is active, including after a proposal is
ready. Use `PUT /v1/meal-chat/sessions/{session_id}/time` with JSON
`{"occurred_at_ms": 1785867600000}`. It returns the full updated session without changing
messages, proposal, readiness, or `client_event_id`. A confirmed session returns HTTP 409;
the timestamp uses the same validation as session creation. Repeating the same active
update is safe. Time updates and confirmation are serialized in SQLite so the confirmed
event cannot diverge from the session timestamp. After a network timeout or an update/
confirm race, use `GET /v1/meal-chat/sessions/{session_id}` as the source of truth before
retrying.

Saving is a separate explicit action:

`POST /v1/meal-chat/sessions/{session_id}/confirm`

It is accepted only when `ready_to_confirm=true` and returns an `IntakeEvent` directly
(not an `item` wrapper). The event contains the session's original `client_event_id`, its
latest pre-confirmation `occurred_at_ms`, meal/carbohydrate fields, and the latest
`analysis_id`; every insulin
field is `null`. Repeating confirmation returns the same event.

### Create an insulin-only event

`POST /v1/insulin-events` is the structured insulin write endpoint. `client_event_id` is
the idempotency key: retrying the same payload returns the same event, while reusing the
UUID for different data returns HTTP 409. Android writes this command to its app-private
durable outbox first, so it can display the record immediately and retry after an offline
period without producing a duplicate.

```json
{
  "client_event_id": "c98178ed-c5ac-41d0-b8d3-dae4fe6bdf26",
  "occurred_at_ms": 1785870000000,
  "insulin_units": 4.5,
  "insulin_name": "NovoRapid"
}
```

`insulin_name` is restricted to `NovoRapid` or `Tresiba`; the backend derives the
corresponding `rapid` or `long` type. Meal and insulin data cannot be combined.

### Create a manual/offline meal event

`POST /v1/meal-events` accepts a structured meal that does not require an AI session:

```json
{
  "client_event_id": "87f35008-6782-493c-a95f-49353dfbdf07",
  "occurred_at_ms": 1785870000000,
  "meal_text": "Buckwheat with chicken",
  "carbs_g": 48.0,
  "portion_g": 300.0
}
```

`portion_g` is optional. When present, the backend persists the original portion and
carbohydrate baseline so later consumed-portion edits remain proportional and never
compound rounding. This command uses the same idempotent `client_event_id` contract as
insulin and is the synchronization target for Android's offline meal outbox. There is
still deliberately no generic `POST /v1/intakes` bypass.

Confirmed AI meal responses include response-only
`ai_confidence` and the nullable `absorption_speed`, `absorption_peak_minutes`,
`absorption_duration_minutes`, and `absorption_confidence`, all derived from the persisted
analysis. The same fields are preserved by full and incremental intake sync.

Meal analysis can additionally return nullable estimates for protein, fat, fiber, and a
continuous carbohydrate-absorption profile (`absorption_speed` from 0 to 1, approximate
peak/duration minutes, and a separate confidence). These values are estimates rather than
an exact glycemic-index measurement. Missing values remain valid for older clients.

### Glucose synchronization and forecasting

`POST /v1/glucose/readings` accepts an idempotent batch:

```json
{
  "utc_offset_minutes": 180,
  "backfill_complete": true,
  "readings": [{
    "reading_id": "stable-source-id",
    "measured_at_ms": 1785870000000,
    "glucose_mg_dl": 116.0,
    "trend_mg_dl_min": 0.2,
    "sensor_id": "optional",
    "sensor_generation": "optional",
    "quality": 1.0,
    "utc_offset_minutes": 180
  }]
}
```

The immutable identity check covers the source ID, time, glucose, and trend. Replaying the
same sample through live and history paths is harmless even if optional sensor metadata,
quality, or UTC offset differs. Reusing an ID for materially different data returns 409.
The per-reading UTC offset takes precedence so a history batch can cross a DST boundary;
the batch-level value remains the fallback for older clients.
Timestamp-canonical IDs (`cgm-<measured_at_ms>`) are the exception: a later native-history
correction updates the sample, invalidates/rebuilds its derived scores, and returns
`updated: 1`. An empty `readings` list is accepted only as an explicit
`backfill_complete: true` boundary. Partial history requests send `false`; live uploads omit
the field. This boundary only completes synchronization; it never starts training. Ingesting,
correcting, or deleting source data cannot modify or replace the active model artifact.
One-minute and five-minute sensors share the same contract: modeling resamples by actual
timestamps onto five-minute feature/target bins. Raw row count is never treated as elapsed
history, so 24 one-minute readings do not masquerade as two hours of coverage.

`GET /v1/forecast/current` returns 24 direct probabilistic points at five-minute steps,
ending at +120 minutes. It also returns confirmed meal/NovoRapid/Tresiba activity profiles;
their peak and end can extend beyond the forecast horizon. The forecast always begins at
the latest real reading. A live request may use a currently known event entered after that
reading (including a backdated event), but the event cannot affect a forecast point before
its occurrence. Historical training/replay additionally requires `created_at_ms` to be at or
before each historical anchor, so a later backdated record cannot leak hindsight into an
earlier forecast. A stale reading (more than 15 minutes old) returns `status=stale` and no
trajectory. Sparse or poor-quality history is marked `low_confidence` and gets wider bands.
A conservative `cold_start` trajectory is available before personalization. Missing sensor
quality is conservatively treated as 0.75. AI meal carbohydrate low/high bounds and
AI/absorption confidence add explicit event uncertainty: a wider or less certain photo
estimate cannot produce a tighter band or higher overall confidence than the corresponding
tight confirmed record.

The same response includes the exact raw `based_on_glucose_mg_dl` and an additive
`alert_assessment`. Its fixed green target is 4.2–9.0 mmol/L (75.6–162.0 mg/dL). A low or
high crossing requires two adjacent five-minute forecast points inside the first 60 minutes.
`possible` means only the corresponding interval edge crossed; `likely` means the median
crossed. Evidence-specific `low_possible`, `low_likely`, `high_possible`, and `high_likely`
preserve both an early interval signal and a later median signal for local sensitivity/horizon
policy. Legacy `low`/`high` remain likely-first summaries. These labels are qualitative, never
percentages. `no_data`, `stale`, delayed, and
`low_confidence` inputs are `unavailable`. The baseline and forecast-approved artifacts without
an explicit checksummed `approval.alert_approved=true` bit remain `shadow`; only a fresh
`ready` champion carrying that separate bit is `delivery_eligible`. The assessment contains no
carbohydrate or insulin recommendation and must not be used to calculate treatment.

`alert_approved` is produced only by the operator-triggered, one-shot prospective evaluator; it
is never learned or changed online. On the same earliest fourteen frozen dense local days, the
backend replays every causal five-minute forecast and evaluates confirmed low/high episodes
(two adjacent readings outside 75.6--162.0 mg/dL). The preregistered
`frozen-14d-episode-alert-v3` gate requires at least five low and five high episodes across at
least four days per direction, at least 80% low and 75% high selected-policy episode recall, no
more than one missed low episode, no more than one selected-policy false alert per day, and at
least 15 minutes median warning lead. Recall, missed lows, false-alert rate, and lead must also
remain within fixed tolerances of the frozen reference, pinned comparator, and current
comparator where that comparator has lead evidence. Replay issues at backend receipt plus a
checksummed 60-second delivery margin, uses that effective time for cooldown and warning lead,
and never credits a crossing already in the past. It preregisters Android's most sensitive
Early policy at the maximum 60-minute horizon (the selectable minimum remains 15 minutes), emits
at most one direction per issue, and applies the same earliest-crossing, likely-evidence, then-low
tie-breaks before per-direction cooldown. Threshold runs without a 15-minute in-target
rearm gap are one episode, and one alert can validate at most one distinct episode; unmatched
repeats remain false alerts. The metrics, thresholds, cohort identity, and
decision are stored inside the artifact checksum. Insufficient alert evidence leaves
`alert_approved=false` but does not prevent an otherwise approved forecast model from being
activated; notifications then remain shadow-only.

Each activity keeps the original summary fields and also exposes `amount`, `unit` (`g` or
`U`), `profile_source`, `profile_confidence`, and exactly 25 `points` from the reading anchor
through +120 minutes in five-minute steps. Every point contains `at_ms`,
`minutes_from_anchor`, signed `contribution_mg_dl` (the event's glucose delta relative to
that forecast anchor), and normalized instantaneous `activity`. New additive effective-action
metadata keeps older clients compatible while making uncertainty explicit: `onset_ms`,
`peak_low_ms`, `peak_high_ms`, `end_low_ms`, `end_high_ms`, `attribution_confidence`,
`identifiability`, `action_model`, and `overlap_count`. `peak_ms` and `end_ms` remain the
central representatives; the interval fields must be used whenever they are available.
The active static predictor deliberately keeps meal, NovoRapid, and Tresiba on bounded
population-prior curves. The available event records are too few and too overlapped to identify
separate personal action profiles safely. Simultaneous records remain separate, every overlap
reduces attribution confidence, meal contribution is non-negative, insulin contribution is
non-positive, and no event contributes before it starts. These curves are explanatory model
estimates, not causal measurements, pharmacokinetic claims, or dosing recommendations.

The manually trained model is a dependency-light NumPy linear ridge residual head with 24
direct horizons. Its 138 causal inputs contain glucose deltas and quality masks from the
detailed two-hour trace, progressively coarser samples through 72 hours,
variability/dynamics summaries, daily harmonics, weekday phase, and current state. It receives
no learned meal/rapid/long channels. A fixed chronological tuning day selects regularization
from the checksummed `10, 30, 100, 300, 1000` grid and independently selects four horizon-band
shrinkage weights. Neither choice sees calibration or retrospective selection days. The
residual is added to event-aware persistence, then those weights can shrink it back toward the
safe reference.
Point-bias calibration is disabled. Exact finite-sample split-conformal 80% intervals and
their order-statistic rank are frozen inside the checksummed artifact. The one-shot prospective
approval may replace only the evaluation/reliability envelope; it never updates weights, blend,
or calibration. Once that decision is final, later CGM values are monitoring scores only and
never update confidence or the active version. Live sensor-quality and meal uncertainty may
only widen the same intrinsic interval bounds used by offline scoring; they do not change its
frozen model calibration. Derived runs/scores are retained for 35 days; source CGM and intake
data are not pruned. A persisted random `server_instance_id` lets Android reset its history
cursor if the backend database is recreated.

Training is never exposed over HTTP and has no background task, timer, ingestion hook, or
online calibrator. The operator CLI and the bounded deployment bootstrap described below are
the only entrypoints. New source data only makes
`training.data_changed_since_training` true. The explicit `active_forecast_model` pin is the
sole runtime selector; a missing, corrupt, rejected, or incompatible artifact fails closed to
the baseline. The pinned artifact continues serving until an operator explicitly activates a
different prospectively approved version or the GitOps bootstrap explicitly activates a
clearly labelled exploratory display-only selection. Evaluation provenance retains the exact
active comparator version and digest, while runtime compatibility binds directly to the
code-owned baseline digest; releases therefore do not form an unbounded dependency chain.
The exact comparator record must still exist when a new active pin is created, but it is not
an ongoing inference dependency after activation.

Manual training requires at least fifteen dense local-day blocks: at least eight early days
for fitting, one separate tuning day, two frozen-calibration days, and four chronologically
held-out retrospective selection
days. Every split boundary has a 120-minute purge. The primary evaluation samples anchors at
least 120 minutes apart and weights calendar days equally; overlapping five-minute windows are
diagnostics only. In the prospective path these four development days can reject a model but
can never approve one; the GitOps path may use them only for an explicitly exploratory,
non-alerting chart selection. A development pass at the latest available reading freezes exactly one `pending` candidate, its
predictor digest, and the exact pinned comparator version/digest. It preregisters the earliest
fourteen complete, dense later local days as a one-shot prospective cohort; no interim or
expanding-prefix choice is allowed. Replay uses each anchor's backend receipt time: history must
already be received, future labels must arrive strictly later, and the anchor must arrive within
the same 15-minute freshness window required by live forecasts. Equal-timestamp bulk uploads and
slow sequential historical backfills are therefore excluded. Intake edits/deletions that cannot
be reconstructed exactly fail closed, atomically terminalize the pending candidate as rejected,
and release the preregistration slot for a newly frozen candidate.

After all fourteen days exist, `evaluate` applies equal-day point, interval, and hypoglycemia
gates against event-aware persistence, the frozen comparator, and any different current
champion. Insufficient preregistered low-glucose evidence is final `inconclusive`, not approval.
A pass changes only the approval/evaluation/reliability envelope and checksum; network weights,
blend, event priors, and frozen calibration retain the same predictor hash. The result becomes
`candidate`, but activation remains a separate explicit operator action. Rejected,
inconclusive, pending, development-only, corrupt, or comparator-orphaned artifacts cannot be
activated.
Run the administration commands from `backend` against the local SQLite database only:

```powershell
python -m scripts.forecast_admin status
python -m scripts.forecast_admin export
python -m scripts.forecast_admin train --candidate-version personal-review-1
python -m scripts.forecast_admin deploy-display --candidate-version display-image-v1
python -m scripts.forecast_admin evaluate personal-review-1
python -m scripts.forecast_admin activate personal-review-1
python -m scripts.forecast_admin rollback
```

Use `--database <path>` before the subcommand to override `JUGGLUCO_DATABASE_PATH`. `export`
uses SQLite's online backup API, removes unrelated chat/forecast-audit tables and transcript
text, then writes a minimized `training-snapshot.sqlite`, ordered glucose/intake CSVs, and a
SHA-256 manifest below ignored `backend/data/exports/`. The export directory is mode 0700 and
its files are mode 0600 on POSIX systems. The command prints only paths and aggregate counts,
never raw glucose or meal values. Add `--output <new-directory>` to select a different
destination. `train` accepts optional `--data-cutoff-ms` and `--candidate-version`, but only a
freeze at the latest available reading can enter `pending`; an older cutoff is development-only
and cannot become runtime-valid. Only one preregistered candidate may be pending, and the next
freeze must occur after the previous fixed cohort. `evaluate VERSION` is local-only, performs no
training or activation, and makes one final decision after the fixed cohort. `activate` pins an
approved candidate; `rollback [version]` pins either
the prior version or an explicitly named existing version. `GET /v1/forecast/status` reports
`training.mode=manual`, `automatic_enabled=false`, whether data changed, data coverage,
30/60/120-minute errors, interval coverage, and rolling 7/30-day error.

`deploy-display` is the narrower idempotent GitOps path. It trains with the deterministic
candidate version, retries a bounded number of concurrent source-revision races, and calls the
separate activation method only when display gates return `accepted`. A skipped or rejected
result normally retains the current safe model; `--require-activation` also returns a failing
exit code so a PostSync hook cannot claim deployment success. The stored approval explicitly
keeps `alert_approved=false`; this command cannot enable forecast notifications. Re-running the
same source revision resumes an accepted-but-not-yet-activated candidate or safely no-ops an
already decided version; a changed source revision can use a deterministic suffixed attempt.

This remains an experimental conditional visualization. It does not calculate a dose and
assumes no unrecorded food, insulin, exercise, illness, or sensor error.

### Synchronize events

`GET /v1/intakes` returns:

```json
{"items": [], "next_sync_version": 0}
```

Optional query parameters:

- `limit` (1-500, default 200);
- `from_ms` and `to_ms` for an inclusive `occurred_at_ms` time window;
- `include_deleted=true` for a full audit view;
- `after_sync_version=<last value>` for incremental sync.

Delta sync always includes soft-deleted tombstones (`deleted=true`) so Android can
remove markers. Use the returned `next_sync_version` as the next cursor. Event details
and soft deletion are available at `GET /v1/intakes/{id}` and
`DELETE /v1/intakes/{id}`.

For a confirmed AI meal with a known full portion, the event also exposes
`portion_g`, `original_portion_g`, and `original_carbs_g`. Correct the amount actually
eaten with the authenticated idempotent endpoint:

`PUT /v1/intakes/{id}/meal-portion`

```json
{"portion_g": 175}
```

The consumed portion cannot exceed the analyzed full portion. The backend always
recalculates `carbs_g` from the immutable original portion/carbohydrate baseline, creates
one new sync revision when the value changes, invalidates the affected forecast history,
and regenerates the current forecast. Repeating the same value is a no-op.

`occurred_at_ms` is a physiological timestamp, not an event identifier. Any number of meals,
NovoRapid doses, and Tresiba doses may share the same millisecond. Each confirmation keeps
its own server `id`, client idempotency UUID, absorption profile, sync revision, forecast
contribution, and activity entry. A graph client may cluster those entries visually, but it
must expand the cluster back to the individual event list and delete only by event `id`.

Deletion is authenticated, idempotent, and returns the same tombstone on retries; an unknown
UUID returns `404` with `intake event not found`. It never erases the linked meal-chat or
analysis audit data. The delete revision forces a fresh immutable current forecast without
the event and marks the static artifact as having newer source data. Deletion itself does not
train or calibrate a model and never invokes any dosing behavior.

## Tests

All tests use a temporary SQLite file and a fake or mocked OpenRouter transport. They
never call a paid model and never need a real API key.

```powershell
python -m pytest -q
```

The suite covers bearer authentication, host filtering, input validation, two-photo
analysis, audio forwarding, exact Android response fields, strict OpenRouter schema and
ZDR options, idempotent insulin-only creation, server-enforced NovoRapid/Tresiba
validation, persistent multi-turn meal chat, non-bypassable meal-only confirmation,
raw-media non-persistence, configured photo limits, deletion, delta sync, idempotent CGM
ingestion, causal 120-minute forecasts, stale/low-quality handling, probabilistic bands,
activity timing, online scoring, and candidate promotion/rejection gates.
