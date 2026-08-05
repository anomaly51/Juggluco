# Juggluco intake backend

This service is the authoritative write path for the redesigned phone intake flow.
It stores confirmed meal/insulin events in SQLite and sends only meal-analysis inputs
to OpenRouter. The Android app must never contain an OpenRouter key.

The service is intentionally bound to the computer's loopback interface and reached
from a USB-connected Android device with `adb reverse`. It is not designed to be
exposed directly to a LAN or the public internet.

## Important security note

If an API key has been pasted into a chat, issue, source file, shell history, or other
shared surface, revoke it and create a new limited key before running this backend.
Never reuse the exposed value. The repository contains no working secret and does not
create a `.env` file.

The OpenRouter request enables Zero Data Retention routing and denies providers that
collect request data. Images are decoded and re-encoded before upload, removing EXIF
and GPS metadata. Raw photos and audio are never written to SQLite or retained by the
application; the multipart runtime may use a short-lived OS temporary file for a large
upload before validation finishes. SQLite still contains health-adjacent event data, so
protect the Windows account and disk.

AI carbohydrate values are estimates. The app must show the range and warnings and
require user confirmation. This backend does not calculate or recommend insulin doses.

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

Meal chat defaults to the economical vision-capable
[`qwen/qwen3-vl-8b-instruct`](https://openrouter.ai/qwen/qwen3-vl-8b-instruct),
selected from OpenRouter's official model catalog with image input, structured-output,
and ZDR routing requirements. It remains configurable with
`OPENROUTER_MEAL_CHAT_MODEL`; `google/gemini-2.5-flash-lite` is a reasonable manual
fallback. There is no silent fallback that could weaken privacy/provider constraints.
Voice transcription defaults to the lower-cost `google/gemini-2.5-flash-lite`, and
the legacy single-request vision endpoint now uses the same Qwen vision default.

Start the backend on loopback only:

```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8765
```

Connect the physical phone to that loopback port without opening a LAN listener:

```powershell
adb -s RFCY90R4HGT reverse tcp:8765 tcp:8765
adb -s RFCY90R4HGT reverse --list
```

The Android base URL is then `http://127.0.0.1:8765`. The emulator can use the same
`adb reverse` command by replacing the serial with `emulator-5554`. The Android client
must send `Authorization: Bearer <JUGGLUCO_API_TOKEN>` on every endpoint except health.

Do not start Uvicorn with `--host 0.0.0.0`. A future remote deployment needs TLS,
per-device credentials, backups, migrations, rate limiting at the edge, and an explicit
privacy/compliance review.

## API contract

### Health

`GET /v1/health` is intentionally unauthenticated and returns only generic readiness
flags. It never returns credentials, provider names, endpoints, or model IDs.

### Transcribe an editable voice draft

`POST /v1/transcriptions` uses `multipart/form-data` with one required `audio`
file field. AAC, FLAC, M4A/MP4, MP3, OGG, and WAV are accepted up to
`JUGGLUCO_MAX_AUDIO_BYTES`. The request requires the same backend Bearer token as
the other protected `/v1` routes. A successful response contains only provider-neutral
editable text:

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
the configured OpenRouter audio model, then the transcript and photos are analyzed by
the configured vision model using a strict JSON schema. A successful response is:

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

`POST /v1/insulin-events` is the only direct write endpoint. `client_event_id` is the
idempotency key: retrying the same payload returns the same event, while reusing the UUID
for different data returns HTTP 409.

```json
{
  "client_event_id": "c98178ed-c5ac-41d0-b8d3-dae4fe6bdf26",
  "occurred_at_ms": 1785870000000,
  "insulin_units": 4.5,
  "insulin_name": "NovoRapid"
}
```

`insulin_name` is restricted to `NovoRapid` or `Tresiba`; the backend derives the
corresponding `rapid` or `long` type. Meal and insulin data cannot be combined. There is
deliberately no generic `POST /v1/intakes`: a meal can be written only by the explicit
meal-chat confirmation endpoint above. Confirmed meal responses include response-only
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
correction updates the sample, invalidates/rebuilds its scores and calibration, and returns
`updated: 1`. An empty `readings` list is accepted only as an explicit
`backfill_complete: true` boundary. Partial history requests send `false`; live uploads omit
the field. Candidate training is queued only after the explicit `true` boundary, so a
multi-request 45-day import is never trained from a prefix and ordinary refreshes still obey
the 24-hour throttle.
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

Each activity keeps the original summary fields and also exposes `amount`, `unit` (`g` or
`U`), `profile_source`, `profile_confidence`, and exactly 25 `points` from the reading anchor
through +120 minutes in five-minute steps. Every point contains `at_ms`,
`minutes_from_anchor`, signed `contribution_mg_dl` (the event's glucose delta relative to
that forecast anchor), and normalized instantaneous `activity`. New additive effective-action
metadata keeps older clients compatible while making uncertainty explicit: `onset_ms`,
`peak_low_ms`, `peak_high_ms`, `end_low_ms`, `end_high_ms`, `attribution_confidence`,
`identifiability`, `action_model`, and `overlap_count`. `peak_ms` and `end_ms` remain the
central representatives; the interval fields must be used whenever they are available.
For baseline and legacy-v2 champions the series uses the conservative prior response curve.
Contextual points require a validated v3 personal champion, at least eight usable independent
training responses of that event kind, and a later chronological holdout containing at least
five independent events and twelve affected windows. The complete candidate must improve
prediction over the same candidate with that event kind ablated; otherwise that kind keeps
the prior series. An enabled series is the guarded counterfactual model difference between
the complete forecast and a forecast with that one immutable event ID omitted, so simultaneous
records stay separate and the same dose may have different sampled points under different
measured context. All intersecting meal, rapid, and long kernels increase `overlap_count` and
reduce attribution confidence instead of being silently merged.

For NovoRapid, a validated neural marginal is projected onto a regularized, bounded effective
kernel that may adjust onset, peak, duration, and amplitude for the current context. The raw
signed counterfactual remains the contribution series; the smooth kernel controls explanatory
timing and activity. Without validated evidence the population or globally learned kernel is
returned with a deliberately wide interval. Tresiba is not assigned a learned sharp per-dose
peak: daily doses are rendered as separate, overlapping broad depot curves with a slow rise,
wide plateau, and slow tail. Context may adapt one strongly bounded shared basal amplitude,
while individual Tresiba timing remains low/not-identifiable. The existing
`profile_source=personalized` wire value is retained for Android compatibility. Meal
contribution is clamped non-negative, insulin contribution non-positive, magnitude is bounded,
and an event has zero contribution before it starts. These are model estimates of effective
glucose impact, not causal measurements, pharmacokinetic claims, or dosing recommendations.

The v3 predictor is still a dependency-light NumPy hybrid. A damped CGM trend and continuous
event-response priors remain the safe fallback. The personal candidate uses a compact shared
encoder plus a gated second-stage residual head and produces all 24 horizons directly. Its
causal feature schema keeps the detailed two-hour CGM trace, adds progressively coarser
samples and variability/dynamics summaries through 72 hours, two daily harmonics, weekday
phase, sensor-quality masks, event channels, and meal/insulin interaction channels. It learns
predictive correlations present in those inputs; it cannot identify an unmeasured cause.
Legacy `personalized-hybrid-mlp-direct-24-v2` champions remain readable and retain their old
feature dimensions. Personal training may update global NovoRapid timing after enough clean
episodes; long-insulin evidence may update only slow basal sensitivity, not a per-injection
Tresiba peak or end. Validation-derived residual scale and a per-reading online residual
calibrator form the uncertainty interval. Forecast runs and points are immutable; later CGM
values are scored separately without changing the historical prediction. Scores and
calibration are scoped to the exact model version. Derived runs/scores are retained for 35
days; source CGM and intake data are not pruned. A persisted random `server_instance_id` in
the status response lets Android reset its history cursor if the backend database is recreated.

Personal training requires at least three occupied days at 80% or better five-minute-bin
density; status remains `learning` until seven occupied days even if a personal model is
already active. Ingestion attempts one bounded candidate training run at most every 24 hours.
New, corrected, or deleted historical events/readings mark the replay dirty; the next explicit
completed-history boundary can then rebuild a candidate without changing source data.
Training uses a chronological holdout. A candidate replaces the
champion only when overall error improves and 30/60/120-minute horizons pass regression
guards; otherwise it is persisted as rejected. Chronological validation reserves at least
48 training windows, a full 24-window/120-minute embargo, and 16 validation windows, so
overlapping targets cannot leak across the split. `POST /v1/forecast/train` triggers the same
candidate path manually for local/debug use. `GET /v1/forecast/status` reports training,
data coverage, 30/60/120-minute errors, interval coverage, and rolling 7/30-day error.

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

`occurred_at_ms` is a physiological timestamp, not an event identifier. Any number of meals,
NovoRapid doses, and Tresiba doses may share the same millisecond. Each confirmation keeps
its own server `id`, client idempotency UUID, absorption profile, sync revision, forecast
contribution, and activity entry. A graph client may cluster those entries visually, but it
must expand the cluster back to the individual event list and delete only by event `id`.

Deletion is authenticated, idempotent, and returns the same tombstone on retries; an unknown
UUID returns `404` with `intake event not found`. It never erases the linked meal-chat or
analysis audit data. The delete revision forces a fresh immutable current forecast without
the event and marks historical event data dirty for a later completed-sync training attempt;
deletion itself does not train a model or invoke any dosing behavior.

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
