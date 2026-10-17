# Android self-updates

The phone app checks the public GitHub Releases feed at startup and every six hours
with a network-constrained JobScheduler job. Downloads resume from private,
no-backup storage. No GitHub token, kubeconfig or backend credentials are shipped.
The More drawer shows the actual build's version at its bottom-right corner and
an Updates control for permission setup, retry and pausing automatic updates.

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
Everyone key. Installed-data capability is retained; rollback, shared UID,
signature permission and authenticator trust for the old key are disabled.
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

