# Juggluco Viewer for iOS

This directory contains the first read-only iOS client for the Juggluco backend. It shows the latest glucose value, a native Swift Charts timeline, the backend's two-hour forecast median and uncertainty interval, and confirmed meal/rapid-insulin/long-insulin events. The fixed green target is 4.2–9.0 mmol/L (75.6–162 mg/dL).

The viewer does not create, edit, or delete health records, calculate a dose, or trigger treatment. Its data is eventually synchronized: it can only show readings and events that Android has already uploaded to the backend. Foreground polling and pull-to-refresh are not a real-time medical alarm channel.

It is an informational visualization, not diagnosis or treatment. Consult the treating physician before making medical decisions or changing therapy. Forecast methodology and limitations are documented in the repository backend README; synchronization may lag or contain gaps. Use the repository's Issues page for support and reproducible bug reports.

## What the first version includes

- current glucose, trend, target state, server-derived age and an explicit stale state;
- 3/6/12/24-hour timeline with line breaks for CGM gaps over ten minutes;
- forecast median plus low/high uncertainty interval, hidden when its anchor expires;
- a read-only, filterable list of meal, rapid-insulin, and long-insulin events from the latest 24-hour snapshot;
- foreground refresh every 60 seconds, immediate refresh on foreground entry, and pull-to-refresh;
- a last-good offline snapshot with a visible cached timestamp and truncation notices;
- HTTPS backend setup and a dedicated viewer token stored in Keychain;
- privacy cover in the app switcher, screen-capture warning, Dynamic Type, VoiceOver summaries, and color-independent status text.

Older backend data remains available through the paginated viewer API, but this MVP intentionally renders the bounded latest-24-hour snapshot only.

## Backend setup

Use the additive read-only backend contract and configure a separate high-entropy token of at least 32 characters:

```text
JUGGLUCO_VIEWER_TOKEN=<random viewer-only secret>
```

Expose the backend through a trusted HTTPS reverse proxy and configure its allowed hosts. Do not expose the SQLite file, development server, or a write-capable Android token directly to the internet. In the app, enter the canonical backend base URL (for example `https://glucose.example.net`) and the viewer token. A token must be entered again whenever the endpoint changes, preventing a saved credential from being sent to a different host by mistake.

The app only calls:

- unauthenticated `GET /v1/health` (without an Authorization header);
- authenticated `GET /v1/viewer/snapshot?glucose_limit=1500&event_limit=100`.

The snapshot contract is:

```text
api_version, server_time_ms, from_ms, to_ms,
target_range,
current_glucose (+ age_ms, is_stale),
glucose_history + glucose_history_order + glucose_history_truncated,
intake_events + intake_events_order + intake_events_truncated,
forecast
```

Canonical values stay in mg/dL in models; the UI divides by 18 only when displaying mmol/L. The backend clock—not the iPhone wall clock—selects the 24-hour window and anchors freshness.

## Build on macOS

Apple's toolchain, iOS Simulator, code signing, and device installation require macOS with Xcode. Windows can edit the sources and run the deterministic repository validator, but it cannot produce or sign an iOS `.ipa` with Apple's supported tools.

Requirements:

- macOS with Xcode 16 or newer for local compilation and tests;
- current Xcode 26 or newer with the iOS 26 SDK for TestFlight/App Store submission under Apple's requirements effective April 28, 2026 ([Apple upcoming requirements](https://developer.apple.com/news/upcoming-requirements/?id=02032026a));
- XcodeGen (`brew install xcodegen`);
- an Apple development team for a physical-device build.

Generate and test the project:

```bash
cd ios
xcodegen generate
xcodebuild \
  -project JugglucoViewer.xcodeproj \
  -scheme JugglucoViewer \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

Open `JugglucoViewer.xcodeproj`, select the `JugglucoViewer` target, choose your team under Signing & Capabilities, and run on a simulator or registered iPhone. Change the bundle identifier if your team does not own `app.juggluco.viewer`. No signing certificate, provisioning profile, token, or `Secrets.xcconfig` belongs in Git.

## Checks available on Windows

From the repository root:

```powershell
python ios/scripts/validate_project.py
```

This verifies the deterministic XcodeGen inputs, privacy manifest, icon dimensions, read-only route policy, token/cache safeguards, and key source/test files. It does not compile Swift; the macOS `xcodebuild test` step remains authoritative.

## Privacy and offline behavior

The repository-hosted [privacy policy](PRIVACY.md) explains the viewer/backend boundary, local storage, deletion, support, and medical limitation. The owner of any distributed build must keep that policy and App Store Connect disclosures aligned with the backend they operate.

- The bearer token uses `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` and is never stored in UserDefaults, a URL, or logs.
- UserDefaults contains only the backend URL and is declared in `PrivacyInfo.xcprivacy` with required-reason code `CA92.1`.
- The last-good health-data cache is scoped by a SHA-256 digest of endpoint plus token, stored in `Library/Caches`, protected with `NSFileProtectionComplete`, excluded from backup, and removed if that exclusion cannot be verified.
- Switching endpoint/token or disconnecting cancels and awaits an in-flight request before clearing the old snapshot, preventing cross-profile data from reappearing.
- URLSession is ephemeral, has no cookies/cache, and rejects redirects so a bearer token cannot move to another origin.
- iOS does not provide an API that guarantees screenshot prevention. The app hides content while inactive and warns while screen capture is active.

## License note

This directory is part of the same repository and follows its GPL-3.0 licensing context. App Store distribution, third-party assets, signing, export-control, privacy disclosures, and GPL/App Store compatibility should receive a separate legal and release review; this README makes no legal determination.
