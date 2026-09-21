"""Migració puntual del flux d'escaneig; idempotent i amb comprovació d'ancoratges."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/comprarador/app/ScanReceiptActivity.kt'
TESTS = ROOT / 'tests/test_scan_flow.py'
PURCHASE_TESTS = ROOT / 'tests/test_purchase_history.py'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) == 1:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise AssertionError(f'Font inesperada a {label}: {text.count(old)} coincidències')


source = SCAN.read_text(encoding='utf-8')
source = replace_once(source,
    '    private lateinit var saveButton: Button\n',
    '    private lateinit var saveButton: Button\n'
    '    private lateinit var addButton: Button\n'
    '    private var recognizedReceipt: ReceiptParseResult? = null\n', 'state')
source = replace_once(source,
    '        body.addView(results, margin())\n        summary = text("", 15f, ink)\n',
    '''        body.addView(results, margin())
        addButton = action(getString(R.string.scan_add_item), false) {
            saved = false
            addCard(rows.size, ReceiptLine("", 0L, 1000L, "ud"))
            saveButton.visibility = View.VISIBLE
            saveButton.isEnabled = true
            updateSummary()
            refreshValidation()
        }.apply { visibility = View.GONE }
        body.addView(addButton, margin(10))
        summary = text("", 15f, ink)
''', 'add item UI')
source = replace_once(source,
    '                    ocrText = result.text\n                    showRows(ReceiptParser.parse(ocrText))\n',
    '''                    ocrText = result.text
                    val parsed = ReceiptParser.parseDetailed(ocrText)
                    showRows(parsed.lines, parsed)
''', 'detailed parse')
source = replace_once(source,
    '    private fun showRows(items: List<ReceiptLine>) {\n        rows.clear()\n',
    '''    private fun showRows(items: List<ReceiptLine>, parsed: ReceiptParseResult) {
        recognizedReceipt = parsed
        rows.clear()
''', 'showRows signature')
source = replace_once(source,
    '        saveButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE\n',
    '        saveButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE\n'
    '        addButton.visibility = View.VISIBLE\n', 'show add button')
source = replace_once(source,
    '        items.forEachIndexed { index, item -> addCard(index, item) }\n        updateSummary()\n',
    '        items.forEachIndexed { index, item -> addCard(index, item) }\n'
    '        updateSummary()\n        refreshValidation()\n', 'validate after OCR')
source = replace_once(source,
    '                refreshRow(row)\n                updateSummary()\n            }\n            override fun afterTextChanged',
    '                refreshRow(row)\n                updateSummary()\n                refreshValidation()\n            }\n            override fun afterTextChanged', 'validate after edit')
source = replace_once(source,
    '    private fun addCard(index: Int, item: ReceiptLine) {\n',
    '''    /** Els totals del tiquet són un control de qualitat de l'OCR, no articles. */
    private fun refreshValidation() {
        if (!::status.isInitialized || saved) return
        val receipt = recognizedReceipt ?: return
        val warnings = mutableListOf<String>()
        val expectedCount = receipt.declaredItemCount
        if (expectedCount != null && expectedCount != rows.size) {
            warnings += getString(R.string.scan_count_warning, expectedCount, rows.size)
        }
        val sum = runCatching {
            rows.fold(0L) { acc, row ->
                Math.addExact(acc, ReceiptParser.moneyMilli(row.price.text.toString()))
            }
        }.getOrNull()
        val expectedTotal = receipt.declaredTotalMilli
        if (sum != null && expectedTotal != null && sum != expectedTotal) {
            warnings += getString(R.string.scan_total_warning, money(expectedTotal), money(sum))
        }
        status.text = when {
            warnings.isNotEmpty() -> warnings.joinToString("\\n")
            rows.isEmpty() -> getString(R.string.no_lines)
            else -> getString(R.string.scan_results)
        }
        status.setTextColor(if (warnings.isNotEmpty()) red else muted)
    }

    private fun addCard(index: Int, item: ReceiptLine) {
''', 'quality check')
SCAN.write_text(source, encoding='utf-8')

source = TESTS.read_text(encoding='utf-8')
source = replace_once(source,
    "self.assertIn('ReceiptParser.parse(ocrText)', SCAN)",
    "self.assertIn('ReceiptParser.parseDetailed(ocrText)', SCAN)", 'scan test contract')
source = replace_once(source,
    "self.assertIn('history.record(key, now, items)', SCAN)",
    "self.assertIn('private fun refreshValidation()', SCAN)\n"
    "        self.assertIn('R.string.scan_add_item', SCAN)\n"
    "        self.assertIn('history.record(key, now, items)', SCAN)", 'new scan contract')
TESTS.write_text(source, encoding='utf-8')

source = PURCHASE_TESTS.read_text(encoding='utf-8')
source = replace_once(source, "self.assertIn('versionCode = 3', gradle)",
    "self.assertIn('versionCode = 5', gradle)", 'versionCode contract')
source = replace_once(source, "self.assertIn('versionName = \"0.1.0\"', gradle)",
    "self.assertIn('versionName = \"0.1.2\"', gradle)", 'versionName contract')
PURCHASE_TESTS.write_text(source, encoding='utf-8')

print('Migració de la interfície OCR i contractes de proves aplicada')
