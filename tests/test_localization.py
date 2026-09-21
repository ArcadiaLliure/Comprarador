import unittest
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
FOLDERS = ["values", "values-es", "values-ca", "values-es", "values-gl", "values-eu", "values-oc", "values-ast", "values-an"]

class LocalesTest(unittest.TestCase):
    def test_same_keys_all_locales(self):
        reference = None
        for folder in FOLDERS:
            path = RES / folder / "strings.xml"
            self.assertTrue(path.is_file(), path)
            rows = ElementTree.parse(path).getroot().findall("string")
            values = {row.get("name"): row.text for row in rows}
            self.assertEqual(len(values), len(rows), folder)
            self.assertTrue(all(values.values()), folder)
            if reference is None:
                reference = set(values)
            self.assertEqual(set(values), reference, folder)

    def test_manifest_and_locale_picker(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn("@string/app_name", manifest)
        self.assertIn("@xml/locales_config", manifest)
        picker = (RES / "xml/locales_config.xml").read_text()
        for tag in ("es", "ca", "gl", "eu", "oc", "ast", "an"):
            self.assertIn('android:name="' + tag + '"', picker)

    def test_catalan_is_one_language_resource(self):
        self.assertFalse((RES / "values-b+ca+ES+valencia").exists())
        config = (RES / "xml/locales_config.xml").read_text()
        self.assertNotIn('android:name="ca-ES-valencia"', config)
        self.assertIn('android:name="ca"', config)
