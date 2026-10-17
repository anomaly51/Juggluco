"""Restore only the pinned sensor libraries documented by the upstream README."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

UPSTREAM_APK_SHA256 = "81b18a3ed48daaa2f6ef6af0a1f06c9eb78d33515bec64d5b43eefc66aa96348"
DEPENDENCIES = json.loads(Path(__file__).with_suffix(".json").read_text(encoding="utf-8"))


def verified_libraries(apk: Path) -> dict[str, bytes]:
    result = {}
    with zipfile.ZipFile(apk) as archive:
        for name, info in DEPENDENCIES.items():
            entry = f"lib/arm64-v8a/{name}"
            if archive.namelist().count(entry) != 1 or archive.getinfo(entry).file_size > 20 * 1024 * 1024:
                raise ValueError(f"Missing, duplicate or oversized sensor library: {name}")
            data = archive.read(entry)
            if hashlib.sha256(data).hexdigest() != info["sha256"]:
                raise ValueError(f"Unexpected sensor library: {name}")
            result[name] = data
    return result


def restore(apk: Path, root: Path) -> None:
    with apk.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != UPSTREAM_APK_SHA256:
            raise ValueError("The upstream APK does not match the pinned 10.9.8 release")
    libraries = verified_libraries(apk)  # Validate everything before writing anything.
    root = root.resolve()
    targets = {}
    for name in libraries:
        target = (root / DEPENDENCIES[name]["destination"]).resolve()
        if not target.is_relative_to(root / "Common" / "src"):
            raise ValueError("Native library destination is outside Common/src")
        targets[name] = target
    for name, target in targets.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(libraries[name])
    print(f"Restored {len(libraries)} SHA-256-verified arm64 sensor libraries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    restore(args.apk, args.root)
