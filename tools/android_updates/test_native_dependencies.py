import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zipfile
import native_dependencies as native


class NativeDependenciesTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.apk = self.root / "synthetic.apk"
        self.data = b"synthetic native library"
        self.pins = {"libtest.so": {
            "destination": "Common/src/main/jniLibs/arm64-v8a/libtest.so",
            "sha256": hashlib.sha256(self.data).hexdigest(),
        }}
        with zipfile.ZipFile(self.apk, "w") as z:
            z.writestr("lib/arm64-v8a/libtest.so", self.data)

    def test_restores_verified_allowlisted_entries_only(self):
        with patch.object(native, "DEPENDENCIES", self.pins), patch.object(native,
                "UPSTREAM_APK_SHA256", hashlib.sha256(self.apk.read_bytes()).hexdigest()):
            native.restore(self.apk, self.root)
            self.assertEqual((self.root / self.pins["libtest.so"]["destination"]).read_bytes(), self.data)

    def test_rejects_different_upstream_apk(self):
        with self.assertRaises(ValueError):
            native.restore(self.apk, self.root)

    def test_rejects_missing_and_modified_libraries(self):
        with patch.object(native, "DEPENDENCIES", self.pins):
            for contents in ({}, {"lib/arm64-v8a/libtest.so": b"modified"}):
                with zipfile.ZipFile(self.apk, "w") as z:
                    for name, data in contents.items():
                        z.writestr(name, data)
                with self.assertRaises(ValueError):
                    native.verified_libraries(self.apk)

    def test_refuses_destination_outside_source_tree(self):
        self.pins["libtest.so"]["destination"] = "../outside.so"
        with patch.object(native, "DEPENDENCIES", self.pins), patch.object(native,
                "UPSTREAM_APK_SHA256", hashlib.sha256(self.apk.read_bytes()).hexdigest()):
            with self.assertRaises(ValueError):
                native.restore(self.apk, self.root)
