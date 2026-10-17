"""Validate a signed APK and generate public, secret-free Android release metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

REPOSITORY = "anomaly51/Juggluco"
MAX_APK_BYTES = 200 * 1024 * 1024
LEGACY_CERTIFICATE = "364faa248fa32da0dafe11ab889415e9388b629b6e946d1a42d24e56c4e6669d"


def validate_installable(manifest_xml: str) -> None:
    if re.search(r"android:testOnly[^\n]*=(?:[^\n]*\))?\s*(?:0xffffffff|0x1|true)\s*$",
                 manifest_xml, re.MULTILINE):
        raise ValueError("testOnly APKs cannot be published for normal self-updates")


def metadata(badging: str, certificate_output: str, expected_certificate: str,
             apk: Path, tag: str) -> dict:
    if not re.fullmatch(r"android-v[0-9]+", tag):
        raise ValueError("Expected an immutable android-v<versionCode> tag")
    code = int(tag.removeprefix("android-v"))
    if not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", apk.name):
        raise ValueError("Invalid release asset name")
    if not 900 <= code <= 2_100_000_000:
        raise ValueError("Invalid Android version code")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_certificate):
        raise ValueError("Configure the expected signing certificate SHA-256")
    cert_lines = [line.strip() for line in certificate_output.splitlines()
                  if "certificate SHA-256 digest:" in line]
    direct = [f"Signer #1 certificate SHA-256 digest: {expected_certificate.lower()}"]
    rotated = {
        f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {expected_certificate.lower()}",
        f"Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {LEGACY_CERTIFICATE}",
    }
    # Build-tools 36 reports v3.1 rotation as SDK-specific signers, not two co-signers.
    # Only the known legacy key may serve old Android; every modern device gets the private key.
    if cert_lines != direct and not (len(cert_lines) == 2 and set(cert_lines) == rotated):
        raise ValueError("Unexpected APK signing certificate")
    if expected_certificate.lower() == LEGACY_CERTIFICATE:
        raise ValueError("Cannot publish with the public development identity")
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    minimum = re.search(r"sdkVersion:'([0-9]+)'", badging)
    target = re.search(r"targetSdkVersion:'([0-9]+)'", badging)
    if not package or not minimum or not target:
        raise ValueError("Cannot read the APK identity")
    if package[1] != "tk.glucodata.debug" or int(package[2]) != code:
        raise ValueError("APK identity or version differs from the release")
    if not 21 <= int(minimum[1]) <= int(target[1]) or int(target[1]) < 35:
        raise ValueError("Unexpected APK SDK range")
    size = apk.stat().st_size
    if not 0 < size <= MAX_APK_BYTES:
        raise ValueError("Invalid APK size")
    with zipfile.ZipFile(apk) as archive:
        libs = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
        if not libs or {name.split("/")[1] for name in libs} != {"arm64-v8a"}:
            raise ValueError("Expected an arm64-v8a APK")
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return {"schema": 1, "builds": [{
        "applicationId": package[1],
        "flavor": "mobileLibre3SiDexNogoogle",
        "buildType": "debug",
        "abi": "arm64-v8a",
        "versionCode": code,
        "versionName": package[3],
        "minSdk": int(minimum[1]),
        "targetSdk": int(target[1]),
        "size": size,
        "sha256": digest.hexdigest(),
        "url": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{apk.name}",
    }]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--certificate", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    # No credentials are passed to either inspection command.
    badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True)
    manifest_xml = subprocess.check_output(
        [args.aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True)
    validate_installable(manifest_xml)
    certs = subprocess.check_output([args.apksigner, "verify", "--print-certs", str(args.apk)], text=True)
    result = metadata(badging, certs, args.certificate, args.apk, args.tag)
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Validated Android build {result['builds'][0]['versionCode']}")


if __name__ == "__main__":
    main()

