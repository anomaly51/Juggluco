# Juggluco clinical-dark phone UI v7

This branch replaces Juggluco's phone presentation layer with a consistent,
minimal clinical-dark interface while retaining its existing glucose, sensor,
storage, alarm, export, synchronization, watch, and record engines. Wear OS and
small-screen code paths keep their established presentation and behavior.

## Primary experience

- Primary navigation is intentionally limited to Overview and More. Records
  and Statistics remain available from More; their stored data and handlers
  were not removed.
- Timeline and phone-side new-record creation are hidden for this iteration.
  Existing records remain readable and editable, and the storage/backend code
  is retained for a future replacement flow.
- DashboardChrome presents the date, sensor entry point, current reading, target
  status, graph range controls, and the native graph without landing-page
  decoration.
- Portrait uses a compact bottom bar. Landscape follows the device orientation
  and changes to a reading column plus navigation rail, leaving most of the
  width to the graph.
- Phone orientation uses `SCREEN_ORIENTATION_FULL_USER`, so portrait,
  landscape, and reverse orientations follow the user's device setting.

## Clinical graph

- The graph uses a graphite surface and a clearly labeled band based on the
  user's configured low/high targets and current unit.
- Curve segments are split where they cross the thresholds: green is in range,
  amber is above range, and coral is below range. Samples, the current marker,
  and the reading state use the same semantics.
- The dashboard exposes 3h, 6h, 8h, 12h, and 24h ranges plus a pinned Now action
  and readable time/value scales.
- Every visible reading can be represented by a restrained ring/core marker.
  Marker density adapts to the 3h-24h range, while all valid readings remain
  available to native hit-testing. Stream, scan, history, calibrated history,
  and preview paths share the same target-aware colours.
- One-finger phone gestures pan only along time and no longer continuously
  rescale the glucose axis. The current Y range is held through pan/fling and
  reset deliberately by pinch, range presets, Now, or graph/target settings.
  Pinch, sample hit-testing, press-and-hold scrub, and double-tap-to-Now remain
  connected to the native renderer.
- The eleven former `ON THE GRAPH` controls now live together under Settings >
  Graph display, grouped as Glucose readings, Recorded events, App
  presentation, range, behaviour, and appearance controls.
- Debug builds show a labeled, in-memory `DEMO / PREVIEW` curve only when no real
  glucose readings are visible. It never enters the database or sensor path and
  disappears when real readings exist. Release builds do not enable it.

## Complete phone design system

- ClinicalUi and DynamicThemeUtils provide one dark surface, typography,
  spacing, cards, fields, buttons, toggles, lists, focus states, and safe-inset
  behavior across programmatic and legacy overlays.
- Records, Search, Export, Statistics, More, Settings, Sensor, reminders,
  sound/vibration, glucose alerts, schedules, floating glucose, calibration,
  labels, amount mapping, themes/colors, diagnostics/logs, Help, date/time
  pickers, active insulin, spoken glucose, watches, and meals use the new phone
  presentation.
- Connection flows were also rebuilt, including web server, Nightscout,
  LibreView and Account ID, TURN, broadcasts, mirror devices and sensors,
  meters, resend date/confirmation, QR/auto-QR, and destructive confirmations.
- Search now uses the system IME, grouped filters, unit-aware validation, and a
  compact result bar instead of the legacy custom keypad.
- Deep routes use the same system too: meal construction and ingredient
  database, sensor details and calibration history, Wear OS/Garmin shortcuts,
  SIBIONICS setup/account retrieval, and NovoPen import.
- English and Russian strings are provided for the new surfaces.

## Functional boundary

The UI continues to call the established Juggluco Java/JNI/native entry points.
Sensor protocols, databases, glucose calculations, alarms, export,
synchronization, and watch communication were not replaced. JNI additions are
limited to presentation state such as graph duration and the render-only debug
preview.

## Build and verification

Final local verification completed successfully on 2026-08-02:

- `MobileLibre3SiDexNogoogleDebug` was rebuilt from current sources.
- The APK contains `libg.so` for arm64-v8a, armeabi-v7a, x86, and x86_64.
- Wear resource linking completed.
- 96 tests across 23 reports passed with 0 failures, errors, or skips.
- `git diff --check` reports no whitespace errors.
- APK SHA-256:
  `AA72E7272D14D005C19C2F74A389042CA2DCC2672A13E7D8421106DAAB261AB2`.
- The final APK was installed on `emulator-5556`; 3h/6h/8h presets,
  horizontal pan with a stable Y range, real two-pointer pinch, adaptive point
  density, portrait/landscape auto-rotation, More, Graph display, and Records
  were smoke-tested with 0 buffered fatal/ANR matches.
- The same APK was installed as an in-place update on Samsung SM-F966B. It
  cold-launched successfully with the existing live history intact, rendered
  the new adaptive markers, and produced no buffered fatal/ANR/native-fatal
  matches. The connected-device stay-awake flag was confirmed active.

An emulator cannot validate a real BLE/NFC glucose sensor, vendor background
restrictions, or real alarm delivery. Those backend paths remain connected but
still require a supported physical-device field test before distribution.

## v8 navigation cleanup

- PHONE More is now intentionally limited to Sensor, Settings, and Photo. List,
  Statistics, Last Scan, Watch, Talk, Mirror, Export, and Leave app remain in
  code but are no longer exposed from that daily-use sheet.
- Settings now separates the focused root into Glucose and display, Alerts and
  data, Connections, Preferences, Technical, and Legacy. Mirror is a first-class
  Connections action; Logging lives under Technical.
- The new Legacy screen preserves nine specialist routes: List, Statistics,
  Last Scan, Export, Watch, Talk, Floating glucose, Reminders, and Number
  Labels. Their existing storage and service handlers are unchanged.
- Help, Intro, and About are hidden from the PHONE Settings root while their
  implementation remains available in source. Floating glucose, Reminders, and
  Number Labels are no longer duplicated on the root.
- Mirror devices now uses one full-page vertical scroll container and reaches
  all controls through Help and Save. Nested Mirror, Watch, and Talk routes
  return to Settings or Legacy without reopening More or hiding the system UI.

The v8 verification on 2026-08-05 rebuilt all four APK ABIs, passed 105 tests
with no failures, linked Wear resources, and exercised More, Settings, Legacy,
Mirror scrolling, Watch, Talk, and their back-stack transitions on an Android
15 Pixel 6 emulator. The final APK SHA-256 is
`2884829C684DF49C3151CDC71DE814489A93C5158DDE737B20F8C580B3B6E539`.

## v9 backend-first food and insulin intake

- The Add destination is now a purpose-built Food & insulin composer rather
  than the legacy amount recorder. It supports an explicit event time,
  rapid/long/other insulin, insulin name and dose, meal text, confirmed
  carbohydrates, two independent photos (food and nutrition label), and a
  voice note.
- Manual and AI-assisted events share one authoritative backend write path.
  The Android client does not call the legacy native amount writer and does
  not contain an OpenRouter credential. Idempotent client event IDs make a
  retry return the same event instead of duplicating medical data.
- Meal analysis remains a reviewable draft. The backend can transcribe audio
  and analyze text plus up to two normalized images through OpenRouter, returns
  a carbohydrate interval, confidence, items, assumptions, and warnings, and
  never calculates or recommends an insulin dose.
- Confirmed meals and insulin are synchronized back to the graph. Compact
  amber carbohydrate and blue insulin markers are drawn at the actual event
  time and open a readable event-detail card when tapped.
- Backend settings validate both public health and the entered bearer token.
  Local HTTP is limited to loopback/emulator addresses; remote endpoints must
  use HTTPS. Changing endpoint identity clears the previous endpoint's cached
  events immediately.
- Photos are re-sampled, orientation-corrected, and re-encoded off the UI
  thread. The backend strips metadata, does not persist raw media, requests
  zero-data-retention routing, and rejects providers that collect request data.
  Voice capture stops when the host is paused, and drafts survive normal
  orientation changes.

The v9 verification on 2026-08-05 passed 111 Android tests and 20 backend
tests, rebuilt arm64-v8a, armeabi-v7a, x86, and x86_64, and exercised the full
manual backend round trip on `emulator-5554`. Voice permission/recording, two
gallery photos, background image normalization, rotation retention, graph
markers, and marker details were also smoke-tested. A runtime dialog-resource
crash found by that test was fixed and covered by a regression contract. The
same APK was installed in place on Samsung SM-F966B with existing glucose data
preserved, loopback forwarding active, backend token validation successful,
and no final fatal/ANR/native-fatal matches. APK SHA-256:
`91105DBEBDC26F1FA771EABDB1381AF20A14DD3507866D1236573E927F7B6CE1`.

Live paid OpenRouter inference was deliberately not called with the credential
posted in chat. That credential must be revoked and a new key configured only
in the ignored backend `.env`; mocked provider tests cover the multimodal and
structured-output contract until that final credentialed field test.
