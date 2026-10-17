"""Find the APK by AGP metadata, never accidentally publish a stale output file."""
import argparse
import json
from pathlib import Path


def find_apk(root: Path, version_code: int) -> Path:
    candidates = []
    for location in (root / "intermediates/apk", root / "outputs/apk"):
        for metadata in location.glob("**/output-metadata.json"):
            data = json.loads(metadata.read_text(encoding="utf-8"))
            if (data.get("applicationId") != "tk.glucodata.debug"
                    or data.get("variantName") != "mobileLibre3SiDexNogoogleDebug"
                    or data.get("artifactType", {}).get("type") != "APK"):
                continue
            for element in data.get("elements", []):
                if element.get("versionCode") != version_code:
                    continue
                file = (metadata.parent / element["outputFile"]).resolve()
                if not file.is_relative_to(root.resolve()) or not file.is_file():
                    raise ValueError("Invalid APK artifact path")
                candidates.append(file)
    if not candidates:
        raise ValueError("No matching APK; refusing to use an older build")
    return max(candidates, key=lambda path: path.stat().st_mtime_ns)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    args = parser.parse_args()
    print(find_apk(args.root, args.version_code))

