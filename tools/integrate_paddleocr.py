#!/usr/bin/env python3
"""Migració idempotent del motor OCR local a PaddleOCR; es pot executar des de CI."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/comprarador/app/ScanReceiptActivity.kt'
APP = ROOT / 'app/build.gradle.kts'
ROOT_GRADLE = ROOT / 'build.gradle.kts'
SETTINGS = ROOT / 'settings.gradle.kts'
IGNORE = ROOT / '.gitignore'
HISTORY_TEST = ROOT / 'tests/test_purchase_history.py'


def replace_once(path: Path, before: str, after: str) -> None:
    old = path.read_text(encoding='utf-8')
    if after in old:
        return
    assert old.count(before) == 1, f'No s\'ha trobat exactament una vegada el punt de canvi: {path} / {before!r}'
    path.write_text(old.replace(before, after), encoding='utf-8')


replace_once(ROOT_GRADLE,
    'id("com.android.application") version "8.7.3" apply false',
    'id("com.android.application") version "8.7.3" apply false\n    id("com.android.library") version "8.7.3" apply false')
replace_once(SETTINGS, 'include(":app")', 'include(":app")\ninclude(":ppocr-sdk")')
replace_once(APP, 'minSdk = 23', 'minSdk = 26')
replace_once(APP, 'versionCode = 6\n        versionName = "0.1.3"',
                   'versionCode = 7\n        versionName = "0.1.4"')
replace_once(APP, '    implementation("com.google.mlkit:text-recognition:16.0.1")',
    '    implementation("com.google.mlkit:text-recognition:16.0.1") // Reserva explícita si PaddleOCR falla\n'
    '    implementation(project(":ppocr-sdk"))\n'
    '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")\n'
    '    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")\n'
    '    implementation("androidx.exifinterface:exifinterface:1.3.7")')
replace_once(IGNORE, '/data/rejects.jsonl',
    '/data/rejects.jsonl\n# Pesos neuronals: s\'incorporen a l\'APK en compilar; no es pugen al repositori.\n/ppocr-sdk/src/main/assets/models/')
replace_once(HISTORY_TEST, "self.assertIn('versionName = \"0.1.3\"', gradle)",
    "self.assertIn('versionName = \"0.1.4\"', gradle)")
replace_once(HISTORY_TEST, "self.assertIn('versionCode = 6', gradle)",
    "self.assertIn('versionCode = 7', gradle)")

scan = SCAN.read_text(encoding='utf-8')
if 'private fun recognizeWithMlKit(uri: Uri)' not in scan:
    anchor = 'import androidx.core.content.FileProvider\n'
    assert scan.count(anchor) == 1
    scan = scan.replace(anchor, anchor +
        'import androidx.lifecycle.lifecycleScope\n'
        'import kotlinx.coroutines.CancellationException\n'
        'import kotlinx.coroutines.launch\n')
    start = scan.index('    private fun recognize(uri: Uri) {')
    end = scan.index('    /** El text queda al dispositiu;', start)
    method = '''    /** PaddleOCR és el motor principal; ML Kit només s'usa com a reserva visible. */
    private fun recognize(uri: Uri) {
        saved = false
        showMessage(getString(R.string.ocr_running))
        lifecycleScope.launch {
            try {
                ocrText = PaddleReceiptOcr.recognize(this@ScanReceiptActivity, uri)
                rawButton.visibility = View.VISIBLE
                android.widget.Toast.makeText(this@ScanReceiptActivity,
                    getString(R.string.ocr_paddle_active), android.widget.Toast.LENGTH_SHORT).show()
                val parsed = ReceiptParser.parseDetailed(ocrText)
                showRows(parsed.lines, parsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@ScanReceiptActivity,
                    getString(R.string.ocr_paddle_fallback, e.message.orEmpty()),
                    android.widget.Toast.LENGTH_LONG).show()
                recognizeWithMlKit(uri)
            }
        }
    }

    private fun recognizeWithMlKit(uri: Uri) {
        try {
            recognizer.process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { result ->
                    val segments = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        line.boundingBox?.let { rect -> ReceiptTextSegment(
                            line.text, rect.left, rect.top, rect.right, rect.bottom
                        ) }
                    }
                    val geometric = ReceiptReadingOrder.reconstruct(segments)
                    val (ordered, parsed) = ReceiptReadingOrder.select(result.text, geometric)
                    ocrText = ordered
                    rawButton.visibility = View.VISIBLE
                    showRows(parsed.lines, parsed)
                }
                .addOnFailureListener { e -> showMessage(getString(R.string.ocr_error, e.message.orEmpty())) }
        } catch (e: Exception) {
            showMessage(getString(R.string.image_error, e.message.orEmpty()))
        }
    }

'''
    scan = scan[:start] + method + scan[end:]
    SCAN.write_text(scan, encoding='utf-8')

assert 'PaddleReceiptOcr.recognize(this@ScanReceiptActivity, uri)' in SCAN.read_text(encoding='utf-8')
assert (ROOT / 'ppocr-sdk/src/main/java/com/paddle/ocr/PaddleOCR.kt').is_file(), 'Falta preparar el SDK'
print('PaddleOCR integrat a Comprarador 0.1.4')
