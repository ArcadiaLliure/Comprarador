"""Comprovacions estructurals; no substitueixen una prova real amb el mòbil."""
import json
import unittest
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/comprarador/app'
SDK = ROOT / 'ppocr-sdk'
MODEL = SDK / 'src/main/assets/models'


class PaddleOcrContractTests(unittest.TestCase):
    def test_sdk_real_and_provenance(self):
        self.assertTrue((SDK / 'src/main/java/com/paddle/ocr/PaddleOCR.kt').is_file())
        self.assertTrue((SDK / 'LICENSE-APACHE-2.0').is_file())
        self.assertIn('dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf',
                      (SDK / 'ORIGEN.md').read_text(encoding='utf-8'))

    def test_models_bundled_for_offline_execution(self):
        manifest = json.loads((MODEL / 'model-provenance.json').read_text(encoding='utf-8'))
        self.assertEqual(manifest['model'], 'PP-OCRv6_small')
        for name in ('det/inference.onnx', 'rec/inference.onnx', 'rec/inference.yml'):
            path = MODEL / name
            self.assertTrue(path.is_file(), path)
            self.assertEqual(path.stat().st_size, manifest['files'][name]['bytes'])
            self.assertGreater(path.stat().st_size, 100_000 if name.endswith('.onnx') else 100)
        self.assertFalse((ROOT / '.gitignore').read_text().find('/ppocr-sdk/src/main/assets/models/') == -1)

    def test_real_engine_invoked_before_fallback(self):
        bridge = (APP / 'PaddleReceiptOcr.kt').read_text(encoding='utf-8')
        scan = (APP / 'ScanReceiptActivity.kt').read_text(encoding='utf-8')
        self.assertIn('PaddleOCR.create(app)', bridge)
        self.assertIn('paddle.recognize(image)', bridge)
        self.assertIn('result.results.map', bridge)
        self.assertIn('ReceiptReadingOrder.reconstruct(segments)', bridge)
        self.assertLess(scan.index('PaddleReceiptOcr.recognize('), scan.index('recognizeWithMlKit(uri)'))
        self.assertIn('lifecycleScope.launch', scan)
        gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('implementation(project(":ppocr-sdk"))', gradle)
        self.assertIn('minSdk = 26', gradle)

    def test_translations_paddle_engine(self):
        keys = None
        for lang in ('values', 'values-es', 'values-gl', 'values-eu', 'values-oc', 'values-ast', 'values-an'):
            path = ROOT / 'app/src/main/res' / lang / 'strings_paddle.xml'
            strings = {e.get('name'): e.text for e in ElementTree.parse(path).getroot()}
            self.assertTrue(all(strings.values()), lang)
            if keys is None:
                keys = set(strings)
            self.assertEqual(keys, set(strings), lang)
            self.assertEqual(keys, {'ocr_paddle_active', 'ocr_paddle_fallback'})


if __name__ == '__main__':
    unittest.main()
