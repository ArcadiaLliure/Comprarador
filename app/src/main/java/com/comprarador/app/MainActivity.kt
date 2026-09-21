package com.comprarador.app

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

/** Prototip Android sense connexió. Els textos de la interfície provenen dels recursos lingüístics del dispositiu. */
class MainActivity : ComponentActivity() {
    private lateinit var catalog: Catalog
    private lateinit var ledger: SavingsLedger
    private lateinit var receiptEdit: EditText
    private lateinit var status: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var summary: TextView
    private lateinit var saveButton: Button
    private lateinit var walletText: TextView
    private val rows = mutableListOf<ItemRow>()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var pendingPhoto: Uri? = null
    private var comparison: Comparison? = null

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingPhoto?.let(::recognize)
        else status.text = getString(R.string.photo_cancelled)
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) recognize(uri)
    }

    private data class ItemRow(
        val name: EditText,
        val amount: EditText,
        val quantity: EditText,
        val unit: Spinner,
        val matches: Spinner,
        var candidates: List<HistoricalProduct> = emptyList()
    )

    private data class Comparison(
        val current: Long,
        val historic: Long,
        val matched: Int,
        val total: Int,
        val coveragePermille: Int,
        val formSignature: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            catalog = Catalog(this)
            ledger = SavingsLedger(this)
        } catch (e: Exception) {
            setContentView(TextView(this).apply {
                text = getString(R.string.catalog_error, e.message.orEmpty())
                setPadding(24, 32, 24, 24)
            })
            return
        }
        buildUi()
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    private fun text(value: String, size: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(22, 53, 46))
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun field(hintText: String, value: String, numeric: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            textSize = 15f
            setSingleLine(true)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val main = vertical().apply {
            setPadding(dp(16), dp(20), dp(16), dp(28))
            setBackgroundColor(Color.rgb(248, 250, 248))
        }
        scroll.addView(main)
        main.addView(text(getString(R.string.app_name), 29f, true))
        main.addView(text(getString(R.string.subtitle), 17f))
        main.addView(text(getString(R.string.privacy), 13f))
        main.addView(text(if (catalog.size < 1000)
            getString(R.string.catalog_demo, catalog.size.toInt())
        else getString(R.string.catalog_full, catalog.size.toInt()), 13f, true))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button(getString(R.string.take_photo)) { startCamera() },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button(getString(R.string.open_image)) { gallery.launch("image/*") },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        main.addView(actions)
        main.addView(text(getString(R.string.receipt_label), 15f, true))
        receiptEdit = EditText(this).apply {
            hint = getString(R.string.receipt_hint)
            minLines = 5
            maxLines = 12
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(getString(R.string.receipt_hint))
        }
        main.addView(receiptEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)))
        status = text(getString(R.string.demo_status), 13f)
        main.addView(status)
        main.addView(button(getString(R.string.analyze)) { analyzeReceipt() })
        rowsContainer = vertical()
        main.addView(rowsContainer)
        main.addView(button(getString(R.string.compare)) { compare() })
        summary = text("", 16f)
        main.addView(summary)
        saveButton = button(getString(R.string.save_saving)) { saveSaving() }.apply { isEnabled = false }
        main.addView(saveButton)
        main.addView(text(getString(R.string.footnote), 12f))
        main.addView(text(getString(R.string.piggy_title), 23f, true))
        walletText = text("", 16f)
        main.addView(walletText)
        main.addView(button(getString(R.string.piggy_refresh)) { updatePiggyBank() })
        main.addView(text(getString(R.string.piggy_note), 12f))
        setContentView(scroll)
        updatePiggyBank()
    }

    private fun startCamera() {
        try {
            val dir = File(cacheDir, "receipts").apply { mkdirs() }
            val photo = File.createTempFile("ticket_", ".jpg", dir)
            pendingPhoto = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
            camera.launch(pendingPhoto!!)
        } catch (e: Exception) {
            status.text = getString(R.string.camera_error, e.message.orEmpty())
        }
    }

    private fun recognize(uri: Uri) {
        status.text = getString(R.string.ocr_running)
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    receiptEdit.setText(result.text)
                    analyzeReceipt()
                    if (rows.isNotEmpty()) status.text = getString(R.string.ocr_done)
                }
                .addOnFailureListener { ex ->
                    status.text = getString(R.string.ocr_error, ex.message.orEmpty())
                }
        } catch (e: Exception) {
            status.text = getString(R.string.image_error, e.message.orEmpty())
        }
    }

    private fun analyzeReceipt() {
        val parsed = ReceiptParser.parse(receiptEdit.text.toString())
        rows.clear()
        rowsContainer.removeAllViews()
        comparison = null
        saveButton.isEnabled = false
        summary.text = ""
        status.text = if (parsed.isEmpty()) getString(R.string.no_lines)
        else getString(R.string.lines_detected, parsed.size)
        parsed.forEachIndexed { index, item -> addItem(index, item) }
    }

    private fun addItem(position: Int, item: ReceiptLine) {
        val group = vertical().apply {
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        group.addView(text(getString(R.string.product_number, position + 1), 17f, true))
        val name = field(getString(R.string.description), item.description)
        group.addView(name)
        val measureLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val amount = field(getString(R.string.price), decimal(item.currentMilli), true)
        val quantity = field(getString(R.string.quantity), decimal(item.quantityMilli), true)
        val units = Spinner(this)
        val unitNames = listOf("ud", "kg", "l")
        units.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitNames)
        units.setSelection(unitNames.indexOf(item.quantityUnit).coerceAtLeast(0))
        measureLine.addView(amount, LinearLayout.LayoutParams(0, dp(58), 1.2f))
        measureLine.addView(quantity, LinearLayout.LayoutParams(0, dp(58), 1f))
        measureLine.addView(units, LinearLayout.LayoutParams(0, dp(58), 0.8f))
        group.addView(measureLine)
        val matches = Spinner(this)
        val row = ItemRow(name, amount, quantity, units, matches)
        rows.add(row)
        group.addView(button(getString(R.string.search_equivalents)) { search(row) })
        group.addView(matches)
        group.addView(text(getString(R.string.excluded_note), 12f))
        rowsContainer.addView(group, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        search(row) // Les propostes no es confirmen automàticament.
    }

    private fun search(row: ItemRow) {
        val unit = row.unit.selectedItem?.toString() ?: "ud"
        row.candidates = catalog.candidates(row.name.text.toString(), unit)
        val options = listOf(getString(R.string.excluded_choice)) + row.candidates.map { p ->
            "${p.retailer.replaceFirstChar { it.uppercase() }} · ${p.name.take(64)}" +
                (if (p.brand.isNotBlank()) " · ${p.brand}" else "") +
                " · ${euro(p.priceMilli)} / ${decimal(p.quantityMilli)} ${p.unit}" +
                " · ${getString(R.string.source_line, p.line)}"
        }
        row.matches.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        row.matches.setSelection(0)
        comparison = null
        if (::saveButton.isInitialized) saveButton.isEnabled = false
    }

    /** La signatura inclou el text revisat, els productes i les equivalències. */
    private fun formSignature(): String = buildString {
        append(receiptEdit.text.toString().trim().replace(Regex("\\s+"), " "))
        for (row in rows) {
            append('\u001f').append(row.name.text)
            append('\u001f').append(row.amount.text)
            append('\u001f').append(row.quantity.text)
            append('\u001f').append(row.unit.selectedItemPosition)
            append('\u001f').append(row.candidates.getOrNull(row.matches.selectedItemPosition - 1)?.id ?: -1)
        }
    }

    private fun compare() {
        comparison = null
        saveButton.isEnabled = false
        var currentAll = 0L
        var currentMatched = 0L
        var historicalMatched = 0L
        var matched = 0
        val details = StringBuilder()
        for (row in rows) {
            val current = try { ReceiptParser.moneyMilli(row.amount.text.toString()) }
                          catch (_: Exception) {
                              summary.text = getString(R.string.invalid_price, row.name.text.toString())
                              return
                          }
            if (current < 0) { summary.text = getString(R.string.negative_price); return }
            currentAll = try { Math.addExact(currentAll, current) }
                         catch (_: ArithmeticException) { summary.text = getString(R.string.math_error); return }
            val selected = row.matches.selectedItemPosition
            if (selected <= 0 || selected > row.candidates.size) continue
            val p = row.candidates[selected - 1]
            val quantity = try { ReceiptParser.moneyMilli(row.quantity.text.toString()) }
                           catch (_: Exception) {
                               summary.text = getString(R.string.invalid_quantity, row.name.text.toString())
                               return
                           }
            if (quantity <= 0 || row.unit.selectedItem.toString() != p.unit || p.quantityMilli <= 0) {
                summary.text = getString(R.string.invalid_measure, row.name.text.toString())
                return
            }
            val historic = try { p.historicalMilli(quantity) }
                          catch (_: Exception) { summary.text = getString(R.string.math_error); return }
            try {
                currentMatched = Math.addExact(currentMatched, current)
                historicalMatched = Math.addExact(historicalMatched, historic)
            } catch (_: ArithmeticException) { summary.text = getString(R.string.math_error); return }
            matched++
            details.append("\n").append(getString(R.string.item_detail,
                row.name.text.toString(), euro(current), euro(historic)))
            details.append("\n  ").append(getString(R.string.item_source, p.retailer, p.line, p.name)).append("\n")
        }
        if (matched == 0 || historicalMatched <= 0L) {
            summary.text = getString(R.string.select_match)
            return
        }
        val diff = try { Math.subtractExact(currentMatched, historicalMatched) }
                   catch (_: ArithmeticException) { summary.text = getString(R.string.math_error); return }
        val pct = BigDecimal(diff).multiply(BigDecimal(100))
            .divide(BigDecimal(historicalMatched), 1, RoundingMode.HALF_UP)
        val coverage = if (currentAll > 0) BigDecimal(currentMatched)
            .multiply(BigDecimal(1000)).divide(BigDecimal(currentAll), 0, RoundingMode.HALF_UP).toInt()
        else 0
        val signedPrice = (if (diff > 0) "+" else "") + euro(diff)
        val signedPct = (if (pct > BigDecimal.ZERO) "+" else "") + formatDecimal(pct)
        summary.text = listOf(
            getString(R.string.compare_header),
            getString(R.string.historical_basket, euro(historicalMatched)),
            getString(R.string.current_basket, euro(currentMatched)),
            getString(R.string.difference, signedPrice, signedPct),
            getString(R.string.coverage, matched, rows.size, formatDecimal(BigDecimal(coverage).divide(BigDecimal(10)))),
            getString(R.string.partial_warning),
            details.toString(),
            if (diff != 0L) getString(R.string.compare_before_save) else getString(R.string.not_saving)
        ).joinToString("\n")
        comparison = Comparison(currentMatched, historicalMatched, matched, rows.size,
            coverage, formSignature())
        saveButton.isEnabled = diff != 0L
    }

    private fun saveSaving() {
        val candidate = comparison ?: run { status.text = getString(R.string.not_saved); return }
        if (candidate.formSignature != formSignature()) {
            saveButton.isEnabled = false
            status.text = getString(R.string.review_saving)
            return
        }
        val saving = try { Math.subtractExact(candidate.historic, candidate.current) }
                     catch (_: ArithmeticException) { status.text = getString(R.string.math_error); return }
        if (saving == 0L) { saveButton.isEnabled = false; status.text = getString(R.string.not_saving); return }
        val now = System.currentTimeMillis()
        // No incloem la data per evitar duplicar un mateix tiquet escanejat un altre dia.
        val payload = candidate.formSignature
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        try {
            val inserted = ledger.record(SavingEvent(
                fingerprint = fingerprint, amountMilli = saving,
                historicalMilli = candidate.historic, currentMilli = candidate.current,
                matchedLines = candidate.matched, totalLines = candidate.total,
                coveragePermille = candidate.coveragePermille, createdAtMs = now
            ))
            status.text = if (inserted) getString(R.string.saving_recorded, euro(saving))
                          else getString(R.string.already_saved)
            saveButton.isEnabled = false
            comparison = null
            updatePiggyBank()
        } catch (e: Exception) {
            status.text = getString(R.string.catalog_error, e.message.orEmpty())
        }
    }

    private fun updatePiggyBank() {
        if (!::walletText.isInitialized) return
        try {
            val periods = listOf(
                R.string.piggy_day to "day", R.string.piggy_week to "week",
                R.string.piggy_month to "month", R.string.piggy_year to "year",
                R.string.piggy_all to "all"
            )
            val lines = periods.map { (label, period) ->
                "${getString(label)}: ${euro(ledger.total(period).amountMilli)}"
            }
            val count = ledger.total("all").purchases
            walletText.text = lines.joinToString("\n") + "\n" +
                (if (count == 0) getString(R.string.piggy_empty)
                 else getString(R.string.piggy_count, count))
        } catch (e: Exception) {
            walletText.text = getString(R.string.catalog_error, e.message.orEmpty())
        }
    }

    private fun decimal(milli: Long): String = BigDecimal(milli)
        .divide(BigDecimal(1000)).stripTrailingZeros().toPlainString().replace('.', ',')

    @Suppress("DEPRECATION")
    private fun uiLocale(): Locale = if (Build.VERSION.SDK_INT >= 24)
        resources.configuration.locales[0] else resources.configuration.locale

    private fun euro(milli: Long): String = NumberFormat.getCurrencyInstance(uiLocale()).apply {
        currency = java.util.Currency.getInstance("EUR")
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal(milli).divide(BigDecimal(1000)))

    private fun formatDecimal(number: BigDecimal): String =
        NumberFormat.getNumberInstance(uiLocale()).apply { maximumFractionDigits = 1 }
            .format(number)

    override fun onDestroy() {
        if (::catalog.isInitialized) catalog.close()
        if (::ledger.isInitialized) ledger.close()
        if (::receiptEdit.isInitialized) recognizer.close()
        super.onDestroy()
    }
}
