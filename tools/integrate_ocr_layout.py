"""Integració puntual del lector OCR geomètric i de la revisió segura del tiquet."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/comprarador/app/ScanReceiptActivity.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
TEST = ROOT / 'tests/test_purchase_history.py'


def replace_once(source: str, before: str, after: str, label: str) -> str:
    if source.count(before) != 1:
        raise AssertionError(f'{label}: {source.count(before)} coincidències')
    return source.replace(before, after, 1)


s = SCAN.read_text(encoding='utf-8')
s = replace_once(s, '    private lateinit var addButton: Button\n',
    '    private lateinit var addButton: Button\n    private lateinit var rawButton: Button\n', 'camp de diagnòstic')
s = replace_once(s,
    '        body.addView(status, margin(14))\n        results = column()\n',
    '''        body.addView(status, margin(8))
        rawButton = action(getString(R.string.scan_review_ocr), false) { reviewOcrText() }
            .apply { visibility = View.GONE }
        body.addView(rawButton, margin(12))
        results = column()
''', 'botó revisió OCR')
s = replace_once(s,
    '''                    ocrText = result.text
                    val parsed = ReceiptParser.parseDetailed(ocrText)
                    showRows(parsed.lines, parsed)
''',
    '''                    // ML Kit ordena blocs, no necessàriament les línies del tiquet.
                    // Recuperem files de les coordenades de les línies, sense inferir dades.
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
''', 'ordre geomètric de ML Kit')
s = replace_once(s, '\n    private fun showRows(items: List<ReceiptLine>, parsed: ReceiptParseResult) {',
    '''
    /** El text queda al dispositiu; només es copia si la persona ho decideix. */
    private fun reviewOcrText() {
        val editor = EditText(this).apply {
            setText(ocrText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            maxLines = 18
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.scan_review_ocr)
            .setView(editor)
            .setPositiveButton(R.string.scan_reparse) { _, _ ->
                ocrText = editor.text.toString()
                val parsed = ReceiptParser.parseDetailed(ocrText)
                saved = false
                showRows(parsed.lines, parsed)
            }
            .setNeutralButton(R.string.scan_copy_ocr) { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR", editor.text))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRows(items: List<ReceiptLine>, parsed: ReceiptParseResult) {''', 'revisió manual del text OCR')
s = replace_once(s,
    '''        val expectedTotal = receipt.declaredTotalMilli
        if (sum != null && expectedTotal != null && sum != expectedTotal) {
            warnings += getString(R.string.scan_total_warning, money(expectedTotal), money(sum))
        }
        status.text = when {''',
    '''        val expectedTotal = receipt.declaredTotalMilli
        if (sum != null && expectedTotal != null && sum != expectedTotal) {
            warnings += getString(R.string.scan_total_warning, money(expectedTotal), money(sum))
        }
        if (expectedCount == null || expectedTotal == null) {
            warnings += getString(R.string.scan_unverified)
        }
        status.text = when {''', 'advertiment de lectura incompleta')
s = replace_once(s,
    '''    private fun saveReceipt() {
        if (saved || rows.isEmpty()) return
        val items = mutableListOf<PurchasedItem>()''',
    '''    private fun saveReceipt(confirmedIncomplete: Boolean = false) {
        if (saved || rows.isEmpty()) return
        val receipt = recognizedReceipt
        if (!confirmedIncomplete && receipt != null) {
            val sum = runCatching { rows.fold(0L) { acc, row ->
                Math.addExact(acc, ReceiptParser.moneyMilli(row.price.text.toString()))
            } }.getOrNull()
            if (receipt.declaredItemCount == null || receipt.declaredTotalMilli == null ||
                receipt.declaredItemCount != rows.size || sum != receipt.declaredTotalMilli) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.scan_incomplete_title)
                    .setMessage(R.string.scan_incomplete_message)
                    .setPositiveButton(R.string.scan_save_anyway) { _, _ -> saveReceipt(true) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
        }
        val items = mutableListOf<PurchasedItem>()''', 'confirmació explícita de tiquet incomplet')
SCAN.write_text(s, encoding='utf-8')

g = GRADLE.read_text(encoding='utf-8')
g = replace_once(g, 'versionCode = 5', 'versionCode = 6', 'codi de versió')
g = replace_once(g, 'versionName = "0.1.2"', 'versionName = "0.1.3"', 'nom de versió')
GRADLE.write_text(g, encoding='utf-8')
t = TEST.read_text(encoding='utf-8')
t = replace_once(t, "self.assertIn('versionName = \"0.1.2\"', gradle)",
    "self.assertIn('versionName = \"0.1.3\"', gradle)", 'prova de nom de versió')
t = replace_once(t, "self.assertIn('versionCode = 5', gradle)",
    "self.assertIn('versionCode = 6', gradle)", 'prova de codi de versió')
TEST.write_text(t, encoding='utf-8')
print('Integració OCR geomètric, revisió, validació i versió 0.1.3 aplicada.')
