#!/usr/bin/env python3
"""Deterministic checks that can run without Xcode or third-party packages."""

from __future__ import annotations

import json
import plistlib
import struct
import sys
from pathlib import Path


IOS_ROOT = Path(__file__).resolve().parents[1]
APP_ROOT = IOS_ROOT / "JugglucoViewer"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def read(path: Path) -> str:
    require(path.is_file(), f"missing required file: {path.relative_to(IOS_ROOT)}")
    return path.read_text(encoding="utf-8")


def validate_project() -> None:
    project = read(IOS_ROOT / "project.yml")
    require('iOS: "17.0"' in project, "deployment target must remain explicit")
    require("PrivacyInfo.xcprivacy" in project, "privacy manifest is not in target resources")
    require("JugglucoViewerTests" in project, "unit-test target is missing")

    with (APP_ROOT / "Resources" / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    ats = info.get("NSAppTransportSecurity", {})
    require(not ats.get("NSAllowsArbitraryLoads", False), "arbitrary network loads must stay disabled")

    with (APP_ROOT / "Resources" / "PrivacyInfo.xcprivacy").open("rb") as stream:
        privacy = plistlib.load(stream)
    require(privacy.get("NSPrivacyTracking") is False, "tracking declaration must be false")
    reasons = {
        reason
        for entry in privacy.get("NSPrivacyAccessedAPITypes", [])
        if entry.get("NSPrivacyAccessedAPIType") == "NSPrivacyAccessedAPICategoryUserDefaults"
        for reason in entry.get("NSPrivacyAccessedAPITypeReasons", [])
    }
    require("CA92.1" in reasons, "UserDefaults required-reason declaration is missing")
    privacy_policy = read(IOS_ROOT / "PRIVACY.md")
    require("Library/Caches" in privacy_policy, "privacy policy does not describe offline cache")
    require("Отключить этот iPhone" in privacy_policy, "privacy policy does not explain local deletion")

    icon_manifest = APP_ROOT / "Resources" / "Assets.xcassets" / "AppIcon.appiconset" / "Contents.json"
    icon = json.loads(read(icon_manifest))
    filename = icon["images"][0].get("filename")
    require(bool(filename), "AppIcon has no image filename")
    icon_path = icon_manifest.parent / filename
    with icon_path.open("rb") as stream:
        header = stream.read(24)
    require(header[:8] == b"\x89PNG\r\n\x1a\n", "AppIcon is not PNG")
    width, height = struct.unpack(">II", header[16:24])
    require((width, height) == (1024, 1024), "AppIcon must be 1024x1024")

    api = read(APP_ROOT / "Services" / "ViewerAPIClient.swift")
    for mutation in ('"POST"', '"PUT"', '"PATCH"', '"DELETE"'):
        require(mutation not in api, f"write method present in viewer API client: {mutation}")
    require('request.httpMethod = "GET"' in api, "viewer client must force GET")
    require('endpoint("v1/viewer/snapshot")' in api, "snapshot route mismatch")
    require('endpoint("v1/health")' in api, "health route mismatch")
    require('name: "glucose_limit", value: "1500"' in api, "one-minute 24h limit mismatch")
    require("completionHandler(nil)" in api, "redirect rejection is missing")
    require("URLSessionConfiguration.ephemeral" in api, "ephemeral URLSession is required")

    keychain = read(APP_ROOT / "Services" / "KeychainTokenStore.swift")
    require("kSecAttrAccessibleWhenUnlockedThisDeviceOnly" in keychain, "Keychain accessibility weakened")
    configuration = read(APP_ROOT / "Services" / "ConfigurationStore.swift")
    require("SHA256.hash" in configuration, "cache must be scoped without storing a raw token")

    cache = read(APP_ROOT / "Services" / "SnapshotCache.swift")
    require(".cachesDirectory" in cache, "health cache must be under Library/Caches")
    require("FileProtectionType.complete" in cache, "complete file protection is required")
    require(".completeFileProtection" in cache, "atomic write must request complete file protection")
    require("isExcludedFromBackup = true" in cache, "backup exclusion is missing")
    require("removeItem(at: fileURL)" in cache, "backup-exclusion failure must remove the cache")

    app = read(APP_ROOT / "App" / "JugglucoViewerApp.swift")
    require("scenePhase != .active" in app, "app-switcher privacy cover is missing")
    require("capturedDidChangeNotification" in app, "capture warning is missing")

    models = read(APP_ROOT / "Models" / "ViewerModels.swift")
    for field in ("ageMs", "isStale", "glucoseHistoryTruncated", "intakeEventsTruncated"):
        require(field in models, f"viewer contract field missing: {field}")

    tests = list((IOS_ROOT / "JugglucoViewerTests").glob("*Tests.swift"))
    require(len(tests) >= 4, "expected model, config, API, and state unit tests")
    swift_count = len(list(APP_ROOT.rglob("*.swift")))
    require(swift_count >= 10, "unexpectedly incomplete Swift source tree")
    print(f"iOS project validation passed: {swift_count} app Swift files, {len(tests)} test suites")


if __name__ == "__main__":
    try:
        validate_project()
    except (AssertionError, KeyError, OSError, ValueError) as error:
        print(f"iOS project validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
