# Android self-updates

The phone app checks the public GitHub Releases feed at startup and every six hours
with a network-constrained JobScheduler job. Downloads resume from private,
no-backup storage. No GitHub token, kubeconfig or backend credentials are shipped.
The More drawer shows the actual build's version at its bottom-right corner and
an Updates control for progress, permission setup and retry. Automatic checks,
downloads and installation attempts are always enabled; there is no opt-out.
Legacy pause preferences are removed on startup and background checks.

## Install behavior

- Android 12+ privately signed builds request USER_ACTION_NOT_REQUIRED.
- Android can still require confirmation; the installer callback is session- and
  nonce-bound, with an explicit non-exported receiver and a notification fallback.
- Android 8+ requires the user to allow this app as an installation source once.
- The publicly available upstream Everyone certificate requires confirmation,
  even on newer Android. Do not treat that shared key as a private release identity.
- Installation waits until MainActivity is on Home, with no editor/back stack,
  modal dialog or active alarm. Background work only prepares the download.
- Replacing an APK stops the old process. MY_PACKAGE_REPLACED restores the existing
  Bluetooth/alarm scheduling path, with a notification fallback. This is NOT a
  guarantee of uninterrupted glucose monitoring or alarm delivery.
- No downgrade, uninstall, database reset, forced OS permission or DND changes.
- Keep backend/database changes compatible across updates; exercise migration,
  rollback-by-higher-version, sensor reconnect and alert recovery on a real phone.

## Signing and first rollout

Existing installations need one bootstrap APK with this updater. It must have
the SAME applicationId and compatible signing key as their installed version.
The current supported channel is tk.glucodata.debug / mobileLibre3SiDexNogoogle /
debug / arm64-v8a. Other variants are deliberately ignored by that channel.

Before shipping: inspect the installed APK certificate, preserve its key, and
decide how to migrate shared-test-key installations. Changing to a new private
key without a valid Android signing lineage is NOT an in-place update.
Never solve this by silently uninstalling the user's glucose data.

The GitHub environment android-updates is restricted to `android-v*` tags and has
five encrypted secrets:

- ANDROID_SIGNING_KEY_B64: base64 of the signing keystore
- ANDROID_SIGNING_ALIAS
- ANDROID_SIGNING_STORE_PASSWORD
- ANDROID_SIGNING_KEY_PASSWORD
- ANDROID_SIGNING_LINEAGE_B64

Set ANDROID_UPDATE_CERT_SHA256 as an environment variable (public certificate
fingerprint, 64 hex characters). CI validates the APK against this expected
identity before publication and never silently publishes its default build key.
The current release identity is pinned in AppUpdateVerifier.RELEASE_CERT. Android
13+ receives that private identity through a v3.1 proof-of-rotation from the old
Everyone key. Installed-data and permission capabilities are retained: Android
requires the latter to transfer ownership of AndroidX's existing private receiver
permission during the upgrade. Rollback, shared UID and authenticator trust for
the old key are disabled. Android 13+ uses native NOT_EXPORTED receiver protection.
Android 12L and older retain the legacy signer and require OS confirmation.
The verifier only accepts the explicitly pinned successor, not any new key that
could have been authorized using the publicly available Everyone key.

`provision_signing.ps1 -UploadToGitHub` creates/reuses the key outside the repo,
restricts its directory ACL to the current Windows account, encrypts the password
using Windows DPAPI and provisions the GitHub environment. It never prints secrets.
Back up this directory together with the Windows account recovery material in an
encrypted offline backup; DPAPI alone is not a portable disaster-recovery backup.
Do not commit keys, passwords or kubeconfigs. Do not regenerate the release identity.

Push an immutable tag android-v<versionCode>, e.g. android-v901, from the reviewed
source commit. Version codes must increase, be >=900 and <=2100000000.
The workflow tests, builds, signs and publishes APK + metadata + matching source
archive. A draft release is made public only after all assets are uploaded.
CI initializes the pinned libjuice submodule and restores the 11 required arm64
sensor binaries from the upstream 10.9.8 APK, as documented in the root README.
Both the upstream APK and each sensor binary are SHA-256 pinned. The final APK
is checked again for all 11 matching libraries and for absence of `testOnly`.
The SDK license marker is copied from the already accepted local NDK 30 installation;
CI does not automatically accept new agreements. The source archive includes libjuice.
No paid services or cluster changes are required. This repo's latest release
must remain the Android update channel; publish unrelated releases as non-latest.

The workflow intentionally refuses to overwrite an existing release.
It also gates publication on the full phone unit-test suite, not only updater tests.
The English-only alert-preview and adaptive intake-width contracts are kept in
the full suite. Never bypass the release test gate.
To retry an unpublished draft, inspect and remove that draft manually first.
To stop rollout, unpublish the latest release; do not mutate shipped APK assets.
Already downloaded/committed updates cannot be remotely recalled.

## Verification

### Mandatory online update screen

On phones running Android 6+, a newer compatible release confirmed by the HTTPS
feed requires updating before ordinary use while internet is validated by Android.
The full-screen update panel has no Close/Skip/disable-auto control. It shares the
normal automatic download, verification, permission and OS-confirmation flow.
Neither the normal nor the required panel exposes a pause/disable control.
The Update button remains available for retry and Android confirmation; it is
not an automatic-update toggle. Download retry backoff and alarm/editor safety
checks still apply.

Foreground release discovery runs at entry/reconnection and every five minutes.
A one-second foreground-only UI tick observes network, alarm and editor state;
it does not send network requests every second. No internet/captive portal/unknown
connectivity immediately fails open on that tick. Android 5 has no validated-
internet signal and keeps the previous non-blocking update behavior. A feed error
alone never invents a new required version. A successful feed with no newer
compatible release clears the cached requirement, including withdrawn releases.

Safety exceptions: sensor acquisition/services are not gated. Critical alerts
hide the update gate and prevent installation. A task/editor already open may be
finished before the gate appears; it is never force-closed and its data is not
discarded. The More drawer itself is safe to close. OS Home, network settings,
installation permissions and Play Protect remain under Android's control.
An installation already committed to Android cannot be recalled by losing internet.
This behavior begins only once a build containing it is installed; older APKs
cannot be made blocking remotely by changing release metadata.

Local:
    gradlew.bat --no-daemon "-Pandroid.injected.build.abi=arm64-v8a" :Common:testMobileLibre3SiDexNogoogleDebugUnitTest --tests "tk.glucodata.AppUpdate*Test" --tests "tk.glucodata.MenusContractTest"
    python -m unittest discover -s tools/android_updates -p "test_*.py"

Device test before enabling unattended private-key releases:
1. Install bootstrap, grant installation-source permission, verify the More footer.
2. Publish a higher version signed with that same private key.
3. Verify offline/resumed downloads; wrong signature/hash/variant must be rejected.
4. Keep an alarm or editor open: installation must wait until it is dismissed.
5. Return Home: verify install and the new version, preserved history/settings,
   sensor reconnect and actual + forecast alarm delivery.
6. Verify denied install permission, denied notifications and OS confirmation.
7. Repeat after reboot and on the folded/unfolded phone.
8. With a newer release known, verify the required screen cannot be skipped via
   Back/outside taps. Upgrade a legacy installation with automatic updates paused:
   the preference is removed and downloads start automatically. No screen offers
   a disable toggle. Reconnect while staying in Home and verify progress updates.
9. Lose internet (including an offline Wi-Fi/captive portal): Home becomes usable
   within one foreground tick. Restore internet: the same required screen returns.
   Start an alert or leave an editor open before discovery: neither is interrupted.
10. Withdraw the feed release: the next successful check clears the gate. A
    failed download keeps a retry action; a completed install must clear the gate
    based on the actual installed version, never just download completion.

