#!/usr/bin/env bash
# Secrets are injected only into this signing step, never Gradle or its cache.
set -euo pipefail
umask 077
: "${ANDROID_HOME:?}" "${RUNNER_TEMP:?}" "${SIGNING_KEY:?}" "${SIGNING_LINEAGE:?}"
: "${KEY_ALIAS:?}" "${STORE_PASSWORD:?}" "${KEY_PASSWORD:?}" "${CERT_SHA256:?}" "${RELEASE_TAG:?}"
key_path="$RUNNER_TEMP/juggluco-update-signing.p12"
lineage_path="$RUNNER_TEMP/juggluco-update-signing.lineage"
trap 'rm -f -- "$key_path" "$lineage_path"' EXIT
printf '%s' "$SIGNING_KEY" | base64 --decode > "$key_path"
printf '%s' "$SIGNING_LINEAGE" | base64 --decode > "$lineage_path"
export LEGACY_PASSWORD=IDontKnow # Upstream's already-public compatibility signer.
mkdir -p release
apk="$(python tools/android_updates/find_apk.py --root Common/build --version-code "${RELEASE_TAG#android-v}")"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
  --ks Common/everyone.keystore --ks-key-alias thealias \
  --ks-pass env:LEGACY_PASSWORD --key-pass env:LEGACY_PASSWORD \
  --next-signer --ks "$key_path" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:STORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --lineage "$lineage_path" --rotation-min-sdk-version 33 --v4-signing-enabled false \
  --out release/juggluco-arm64-v8a.apk "$apk"
python tools/android_updates/release_manifest.py \
  --apk release/juggluco-arm64-v8a.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" \
  --certificate "$CERT_SHA256" --tag "$RELEASE_TAG" \
  --out release/android-updates.json
git archive --format=tar.gz --prefix=Juggluco/ HEAD > release/source.tar.gz
