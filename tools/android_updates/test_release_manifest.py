import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("release_manifest", Path(__file__).with_name("release_manifest.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.apk = Path(self.folder.name) / "juggluco-arm64-v8a.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libtest.so", b"synthetic")
        self.cert = "a" * 64
        self.cert_output = f"Signer #1 certificate SHA-256 digest: {self.cert}"
        self.badging = ("package: name='tk.glucodata.debug' versionCode='901' versionName='10.9.9'\n"
                        "sdkVersion:'21'\ntargetSdkVersion:'36'\n")

    def create(self, **changes):
        args = dict(badging=self.badging, certificate_output=self.cert_output,
                    expected_certificate=self.cert, apk=self.apk, tag="android-v901")
        return module.metadata(**(args | changes))

    def test_manifest_matches_artifact(self):
        build = self.create()["builds"][0]
        self.assertEqual(build["versionCode"], 901)
        self.assertEqual(build["size"], self.apk.stat().st_size)
        self.assertEqual(len(build["sha256"]), 64)
        self.assertEqual(build["url"], "https://github.com/anomaly51/Juggluco/releases/download/android-v901/juggluco-arm64-v8a.apk")

    def test_rejects_wrong_key(self):
        with self.assertRaises(ValueError):
            self.create(expected_certificate="b" * 64)

    def test_accepts_sdk_specific_rotation_and_rejects_untrusted_legacy_signer(self):
        output = (f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {self.cert}\n"
                  f"Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {module.LEGACY_CERTIFICATE}")
        self.assertEqual(self.create(certificate_output=output)["builds"][0]["versionCode"], 901)
        for invalid in (output.replace(module.LEGACY_CERTIFICATE, "b" * 64),
                        output.replace("minSdkVersion=33", "minSdkVersion=34"),
                        output + "\n" + self.cert_output):
            with self.subTest(output=invalid), self.assertRaises(ValueError):
                self.create(certificate_output=invalid)

    def test_refuses_public_key_as_release_identity(self):
        with self.assertRaises(ValueError):
            self.create(expected_certificate=module.LEGACY_CERTIFICATE,
                        certificate_output=f"Signer #1 certificate SHA-256 digest: {module.LEGACY_CERTIFICATE}")

    def test_rejects_missing_signer_or_multiple_signers(self):
        for output in ("", self.cert_output + "\n" + self.cert_output.replace("#1", "#2")):
            with self.subTest(output=output), self.assertRaises(ValueError):
                self.create(certificate_output=output)

    def test_rejects_package_or_version_mismatch(self):
        for badging in (self.badging.replace("tk.glucodata.debug", "tk.glucodata"),
                        self.badging.replace("versionCode='901'", "versionCode='902'")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                self.create(badging=badging)

    def test_rejects_malformed_tags(self):
        for tag in ("primary", "android-v1", "android-v901;echo", "android-v9999999999"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.create(tag=tag)

    def test_rejects_wrong_abi(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("lib/x86_64/libtest.so", b"synthetic")
        with self.assertRaises(ValueError):
            self.create()

    def test_refuses_test_only_apks(self):
        module.validate_installable("A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff")
        module.validate_installable("A: android:testOnly(0x01010272)=(type 0x12)0x0")
        for value in ("0xffffffff", "0x1", "true"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.validate_installable(f"A: android:testOnly(0x01010272)=(type 0x12){value}\n")


if __name__ == "__main__":
    unittest.main()

