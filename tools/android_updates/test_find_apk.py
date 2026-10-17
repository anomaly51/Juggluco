import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("find_apk", Path(__file__).with_name("find_apk.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FindApkTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)

    def artifact(self, location, code, output="app.apk"):
        directory = self.root / location / "variant"
        directory.mkdir(parents=True)
        apk = directory / "app.apk"
        apk.write_bytes(b"synthetic")
        (directory / "output-metadata.json").write_text(json.dumps({
            "applicationId": "tk.glucodata.debug",
            "variantName": "mobileLibre3SiDexNogoogleDebug",
            "artifactType": {"type": "APK"},
            "elements": [{"versionCode": code, "outputFile": output}],
        }), encoding="utf-8")
        return apk.resolve()

    def test_ignores_stale_standard_output(self):
        self.artifact("outputs/apk", 898)
        expected = self.artifact("intermediates/apk", 900)
        self.assertEqual(module.find_apk(self.root, 900), expected)

    def test_never_falls_back_to_wrong_version(self):
        self.artifact("outputs/apk", 898)
        with self.assertRaises(ValueError):
            module.find_apk(self.root, 900)

    def test_refuses_output_path_escape(self):
        self.artifact("outputs/apk", 900, "../../../../../outside.apk")
        with self.assertRaises(ValueError):
            module.find_apk(self.root, 900)


if __name__ == "__main__":
    unittest.main()

