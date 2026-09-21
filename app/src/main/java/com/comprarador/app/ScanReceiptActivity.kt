package com.comprarador.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

/**
 * Escaneig local: la càmera s'obre en entrar, sense exemples ni formulari de text brut.
 * Els indicadors ↗ ↘ = comparen preus PER UNITAT amb la darrera compra del mateix article.
 * El catàleg del 2003 només serveix per a una equivalència explícitament confirmada.
 */
class ScanReceiptActivity : ComponentActivity() {
    private val green = Color.rgb(28, 104, 75)
    private val ink = Color.rgb(26, 52, 44)
    private val muted = Color.rgb(105, 122, 114)
    private val paper = Color.rgb(246, 248, 244)
    private val red = Color.rgb(183, 54, 51)
    private val downGreen = Color.rgb(21, 126, 81)
    private val orange = Color.rgb(183, 111, 24)
    private lateinit var catalog: Catalog
    private lateinit var history: PurchaseHistory
    private lateinit var ledger: SavingsLedger
    private lateinit var insights: PurchaseInsights
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var summary: TextView
    private lateinit var saveButton: Button
    private lateinit var addButton: Button
    private lateinit var rawButton: Button
    private var recognizedReceipt: ReceiptParseResult? = null
    private val rows = mutableListOf<ScanRow>()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var photoUri: Uri? = null
    private var ocrText = ""
    private var saved = false

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) photoUri?.let(::recognize) ?: showMessage(getString(R.string.image_error, "URI"))
        else showMessage(getString(R.string.photo_cancelled))
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) recognize(uri)
    }

    private data class ScanRow(
        val name: EditText,
        val price: EditText,
        val quantity: EditText,
        val unit: Spinner,
        val matches: Spinner,
        val trend: TextView,
        val historical: TextView,
        val detail: Button,
        var candidates: List<HistoricalProduct> = emptyList(),
        var previous: PurchaseInsights.PreviousPurchase? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            catalog = Catalog(this)
            history = PurchaseHistory(this)
            ledger = SavingsLedger(this)
            insights = PurchaseInsights(history.readableDatabase)
        } catch (e: Exception) {
            setContentView(TextView(this).apply {
                text = getString(R.string.catalog_error, e.message.orEmpty())
                setPadding(24, 32, 24, 24)
            })
            return
        }
        photoUri = savedInstanceState?.getString("photo_uri")?.let(Uri::parse)
        window.statusBarColor = green
        window.navigationBarColor = paper
        buildScreen()
        if (savedInstanceState == null) window.decorView.post { startCamera() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        photoUri?.let { outState.putString("photo_uri", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::catalog.isInitialized) catalog.close()
        if (::history.isInitialized) history.close()
        if (::ledger.isInitialized) ledger.close()
        super.onDestroy()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun margin(bottom: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(bottom)
    }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun action(title: String, solid: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        setTextColor(if (solid) Color.WHITE else green)
        background = shape(if (solid) green else Color.WHITE, 15)
        minHeight = dp(50)
        setOnClickListener { onClick() }
    }
    private fun input(value: String, hintValue: String, number: Boolean = false) = EditText(this).apply {
        setText(value)
        hint = hintValue
        setSingleLine(true)
        textSize = 15f
        setTextColor(ink)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun money(milli: Long) = NumberFormat.getCurrencyInstance(Locale.getDefault()).format(milli / 1000.0)
    private fun decimal(milli: Long) = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 3
    }.format(milli / 1000.0)
    private fun showMessage(message: String) { if (::status.isInitialized) status.text = message }

    private fun buildScreen() {
        val root = column().apply { setBackgroundColor(paper) }
        val header = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(green, Color.rgb(19, 77, 66))).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat())
            }
            setPadding(dp(22), dp(24), dp(22), dp(26))
        }
        header.addView(text(getString(R.string.home_scan), 29f, Color.WHITE, true))
        header.addView(text(getString(R.string.scan_ready), 14f, Color.rgb(223, 240, 225)))
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = column().apply { setPadding(dp(16), dp(18), dp(16), dp(32)) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action(getString(R.string.take_photo)) { startCamera() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        actions.addView(action(getString(R.string.open_image), false) { gallery.launch("image/*") },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        body.addView(actions, margin(14))
        status = text(getString(R.string.scan_ready), 14f, muted)
        body.addView(status, margin(8))
        rawButton = action(getString(R.string.scan_review_ocr), false) { reviewOcrText() }
            .apply { visibility = View.GONE }
        body.addView(rawButton, margin(12))
        results = column()
        body.addView(results, margin())
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
        body.addView(summary, margin())
        saveButton = action(getString(R.string.scan_save)) { saveReceipt() }.apply { visibility = View.GONE }
        body.addView(saveButton, margin())
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun startCamera() {
        try {
            val dir = File(cacheDir, "receipts").apply { mkdirs() }
            val file = File.createTempFile("ticket_", ".jpg", dir)
            photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            camera.launch(photoUri!!)
        } catch (e: Exception) {
            showMessage(getString(R.string.camera_error, e.message.orEmpty()))
        }
    }

    /** PaddleOCR és el motor principal; ML Kit només s'usa com a reserva visible. */
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

    private fun showRows(items: List<ReceiptLine>, parsed: ReceiptParseResult) {
        recognizedReceipt = parsed
        rows.clear()
        results.removeAllViews()
        saveButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        addButton.visibility = View.VISIBLE
        saveButton.isEnabled = items.isNotEmpty()
        summary.text = ""
        showMessage(if (items.isEmpty()) getString(R.string.no_lines) else getString(R.string.scan_results))
        items.forEachIndexed { index, item -> addCard(index, item) }
        updateSummary()
        refreshValidation()
    }

    /** Els totals del tiquet són un control de qualitat de l'OCR, no articles. */
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
        if (expectedCount == null || expectedTotal == null) {
            warnings += getString(R.string.scan_unverified)
        }
        status.text = when {
            warnings.isNotEmpty() -> warnings.joinToString("\n")
            rows.isEmpty() -> getString(R.string.no_lines)
            else -> getString(R.string.scan_results)
        }
        status.setTextColor(if (warnings.isNotEmpty()) red else muted)
    }

    private fun addCard(index: Int, item: ReceiptLine) {
        val card = column().apply {
            background = shape(Color.WHITE)
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        card.addView(text(getString(R.string.product_number, index + 1), 13f, muted, true), margin(6))
        val name = input(item.description, getString(R.string.description))
        card.addView(name, margin(7))
        val fields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val price = input(decimal(item.currentMilli), getString(R.string.price), true)
        val quantity = input(decimal(item.quantityMilli), getString(R.string.quantity), true)
        val unit = Spinner(this).apply {
            adapter = ArrayAdapter(this@ScanReceiptActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("ud", "kg", "l"))
            setSelection(listOf("ud", "kg", "l").indexOf(item.quantityUnit).coerceAtLeast(0))
        }
        fields.addView(price, LinearLayout.LayoutParams(0, dp(53), 1.1f))
        fields.addView(quantity, LinearLayout.LayoutParams(0, dp(53), 0.9f))
        fields.addView(unit, LinearLayout.LayoutParams(0, dp(53), 0.65f))
        card.addView(fields, margin(8))
        val trend = text("", 17f, muted, true)
        card.addView(trend, margin(5))
        val historical = text(getString(R.string.scan_no_match), 13f, muted)
        card.addView(historical, margin(10))
        val matches = Spinner(this)
        val detail = action(getString(R.string.scan_detail), false) {
            val id = rows.getOrNull(index)?.previous?.productId ?: return@action
            startActivity(Intent(this, TopPurchasesActivity::class.java).putExtra("product_id", id))
        }.apply { isEnabled = false }
        val row = ScanRow(name, price, quantity, unit, matches, trend, historical, detail)
        rows.add(row)
        card.addView(action(getString(R.string.search_equivalents), false) { searchMatches(row) }, margin(5))
        card.addView(matches, margin(9))
        card.addView(detail, margin(0))
        results.addView(card, margin(13))
        searchMatches(row)
        matches.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = refreshRow(row)
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshRow(row)
        }
        unit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (unit.selectedItem?.toString() != item.quantityUnit) searchMatches(row)
                refreshRow(row)
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (saved) return
                refreshRow(row)
                updateSummary()
                refreshValidation()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        name.addTextChangedListener(watcher)
        price.addTextChangedListener(watcher)
        quantity.addTextChangedListener(watcher)
        refreshRow(row)
    }

    private fun selected(row: ScanRow): HistoricalProduct? =
        row.candidates.getOrNull(row.matches.selectedItemPosition - 1)

    private fun searchMatches(row: ScanRow) {
        val unit = row.unit.selectedItem?.toString() ?: "ud"
        row.candidates = catalog.candidates(row.name.text.toString(), unit)
        val entries = listOf(getString(R.string.excluded_choice)) + row.candidates.map { product ->
            "${product.retailer} · ${product.name.take(55)} · ${money(product.priceMilli)} / ${decimal(product.quantityMilli)} ${product.unit}"
        }
        row.matches.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)
        row.matches.setSelection(0)
        refreshRow(row)
    }

    private fun refreshRow(row: ScanRow) {
        val unit = row.unit.selectedItem?.toString() ?: "ud"
        val match = selected(row)
        val current = runCatching { ReceiptParser.moneyMilli(row.price.text.toString()) }.getOrNull()
        val quantity = runCatching { ReceiptParser.moneyMilli(row.quantity.text.toString()) }.getOrNull()
        row.historical.text = if (match == null || quantity == null || quantity <= 0 || match.unit != unit)
            getString(R.string.scan_no_match)
        else runCatching { getString(R.string.scan_historic, money(match.historicalMilli(1000)), unit) }
            .getOrDefault(getString(R.string.scan_no_match))
        val previous = insights.previous(row.name.text.toString(), unit, match?.id)
        row.previous = previous
        row.detail.isEnabled = previous != null
        if (previous == null || current == null || quantity == null || quantity <= 0) {
            row.trend.text = getString(R.string.scan_new)
            row.trend.setTextColor(muted)
            return
        }
        val difference = runCatching {
            PurchaseInsights.variation(PurchaseInsights.unitPrice(current, quantity), previous.unitPriceMilli)
        }.getOrNull()
        if (difference == null) {
            row.trend.text = getString(R.string.math_error)
            row.trend.setTextColor(muted)
            return
        }
        val label = when {
            difference > 0 -> getString(R.string.scan_up, money(difference), unit)
            difference < 0 -> getString(R.string.scan_down, money(-difference), unit)
            else -> getString(R.string.scan_equal, money(0), unit)
        }
        row.trend.text = "$label  ·  ${getString(R.string.scan_previous, money(previous.unitPriceMilli), unit)}"
        row.trend.setTextColor(when { difference > 0 -> red; difference < 0 -> downGreen; else -> orange })
    }

    /** La referència del 2003 queda separada de l'indicador de la darrera compra. */
    private fun updateSummary() {
        if (!::summary.isInitialized || rows.isEmpty()) return
        var current = 0L
        var historical = 0L
        var matched = 0
        for (row in rows) {
            val price = runCatching { ReceiptParser.moneyMilli(row.price.text.toString()) }.getOrNull() ?: continue
            val qty = runCatching { ReceiptParser.moneyMilli(row.quantity.text.toString()) }.getOrNull() ?: continue
            val match = selected(row) ?: continue
            if (price < 0L || qty <= 0L || match.unit != row.unit.selectedItem?.toString()) continue
            val old = runCatching { match.historicalMilli(qty) }.getOrNull() ?: continue
            current = runCatching { Math.addExact(current, price) }.getOrElse { return }
            historical = runCatching { Math.addExact(historical, old) }.getOrElse { return }
            matched++
        }
        summary.text = if (matched == 0) getString(R.string.scan_no_match)
        else getString(R.string.scan_summary, matched, rows.size, money(current), money(historical),
            money(historical - current))
    }

    private fun fingerprint(purchases: List<PurchasedItem>): String {
        val payload = buildString {
            append(ocrText.trim().replace(Regex("\\s+"), " "))
            for (item in purchases) {
                append('\u001f').append(item.description.trim())
                append('\u001f').append(item.currentMilli)
                append('\u001f').append(item.quantityMilli)
                append('\u001f').append(item.unit)
                append('\u001f').append(item.historicalId ?: -1L)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun saveReceipt(confirmedIncomplete: Boolean = false) {
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
        val items = mutableListOf<PurchasedItem>()
        var currentAll = 0L
        var currentMatched = 0L
        var historicalMatched = 0L
        var matched = 0
        try {
            for (row in rows) {
                val name = row.name.text.toString().trim()
                val current = ReceiptParser.moneyMilli(row.price.text.toString())
                val quantity = ReceiptParser.moneyMilli(row.quantity.text.toString())
                val unit = row.unit.selectedItem?.toString() ?: ""
                require(name.isNotBlank() && current >= 0L && quantity > 0L && unit in setOf("ud", "kg", "l"))
                val match = selected(row)
                require(match == null || (match.unit == unit && match.quantityMilli > 0L))
                val historicUnit = match?.historicalMilli(1000)
                items.add(PurchasedItem(name, current, quantity, unit, match?.id, historicUnit))
                currentAll = Math.addExact(currentAll, current)
                if (match != null) {
                    currentMatched = Math.addExact(currentMatched, current)
                    historicalMatched = Math.addExact(historicalMatched, match.historicalMilli(quantity))
                    matched++
                }
            }
        } catch (_: Exception) {
            showMessage(getString(R.string.scan_invalid))
            return
        }
        val key = fingerprint(items)
        val now = System.currentTimeMillis()
        try {
            // El saldo nominal només es desa si hi ha equivalències confirmades.
            // Tiquet i guardiola tenen bases diferents: un reintent repara un desament parcial.
            val recordedSaving = if (matched > 0) {
                val coverage = if (currentAll > 0L) BigDecimal(currentMatched)
                    .multiply(BigDecimal(1000)).divide(BigDecimal(currentAll), 0, RoundingMode.HALF_UP)
                    .toInt().coerceIn(0, 1000) else 0
                ledger.record(SavingEvent(fingerprint = key,
                    amountMilli = Math.subtractExact(historicalMatched, currentMatched),
                    historicalMilli = historicalMatched, currentMilli = currentMatched,
                    matchedLines = matched, totalLines = items.size, coveragePermille = coverage,
                    createdAtMs = now))
            } else false
            val recordedHistory = history.record(key, now, items)
            saved = true
            saveButton.isEnabled = false
            showMessage(if (recordedSaving || recordedHistory) getString(R.string.scan_saved)
                        else getString(R.string.already_saved))
            // No recalcularem les fletxes: la compra acabada de desar no és la seva compra anterior.
            rows.forEach { row ->
                val item = insights.previous(row.name.text.toString(), row.unit.selectedItem?.toString() ?: "ud", selected(row)?.id)
                row.detail.isEnabled = item != null
                if (item != null) row.previous = item
            }
        } catch (e: Exception) {
            showMessage(getString(R.string.scan_error, e.message.orEmpty()))
        }
    }
}
