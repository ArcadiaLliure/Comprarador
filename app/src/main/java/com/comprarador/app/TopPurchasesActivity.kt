package com.comprarador.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** El mateix detall s'obre des del rànquing i des de les targetes de l'escaneig. */
class TopPurchasesActivity : ComponentActivity() {
    private val ink = Color.rgb(27, 55, 48)
    private val muted = Color.rgb(104, 123, 116)
    private val green = Color.rgb(31, 112, 82)
    private val background = Color.rgb(245, 248, 245)
    private lateinit var history: PurchaseHistory
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var period = "all"
    private var selected: FrequentItem? = null
    private var directlyOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = PurchaseHistory(this)
        window.statusBarColor = green
        window.navigationBarColor = background
        val id = intent.getLongExtra("product_id", -1L)
        val directItem = if (id > 0L) PurchaseInsights(history.readableDatabase).item(id) else null
        if (directItem != null) {
            directlyOpened = true
            selected = directItem
            renderDetail(directItem)
        } else renderList()
    }

    override fun onDestroy() {
        if (::history.isInitialized) history.close()
        super.onDestroy()
    }

    @Deprecated("Gestionat per a mantenir la navegació enrere a Android antics")
    override fun onBackPressed() {
        if (directlyOpened) finish()
        else if (selected != null) { selected = null; renderList() }
        else super.onBackPressed()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun label(value: String, size: Float = 15f, bold: Boolean = false, color: Int = ink) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            includeFontPadding = true
        }

    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun margin(bottom: Int = 12) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun surface() = vertical().apply {
        background = shape(Color.WHITE)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        elevation = dp(2).toFloat()
    }

    private fun money(milli: Long) = NumberFormat.getCurrencyInstance(Locale.getDefault()).format(milli / 1000.0)
    private fun decimal(milli: Long) = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 3
    }.format(milli / 1000.0)
    private fun date(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(ms))

    private fun page(title: String, subtitle: String): LinearLayout {
        root = vertical().apply { setBackgroundColor(this@TopPurchasesActivity.background) }
        val header = vertical().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(green, Color.rgb(21, 78, 68))).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat())
            }
            setPadding(dp(22), dp(24), dp(22), dp(26))
        }
        header.addView(label(title, 29f, true, Color.WHITE))
        header.addView(label(subtitle, 14f, false, Color.rgb(218, 242, 227)))
        root.addView(header)
        setContentView(root)
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        val body = vertical().apply { setPadding(dp(16), dp(18), dp(16), dp(26)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return body
    }

    private fun renderList() {
        selected = null
        val body = page(getString(R.string.top_title), getString(R.string.top_subtitle))
        search = EditText(this).apply {
            hint = getString(R.string.top_search)
            setSingleLine(true)
            textSize = 16f
            background = shape(Color.WHITE, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextColor(ink)
            setHintTextColor(muted)
        }
        body.addView(search, margin(15))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("all" to R.string.top_period_all, "week" to R.string.top_period_week,
            "month" to R.string.top_period_month, "year" to R.string.top_period_year)
        names.forEach { (key, textId) ->
            val chip = Button(this).apply {
                text = getString(textId)
                isAllCaps = false
                textSize = 11f
                minHeight = dp(42)
                setPadding(dp(2), 0, dp(2), 0)
                setTextColor(if (key == period) Color.WHITE else green)
                background = shape(if (key == period) green else Color.WHITE, 14)
                setOnClickListener {
                    period = key
                    val query = search.text.toString()
                    renderList()
                    search.setText(query)
                    search.setSelection(query.length)
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(45), 1f).apply { rightMargin = dp(5) })
        }
        body.addView(chips, margin(18))
        list = vertical()
        body.addView(list)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val items = history.ranking(search.text.toString(), period)
        if (items.isEmpty()) {
            list.addView(surface().apply {
                addView(label(getString(R.string.top_empty), 16f, false, muted))
            }, margin())
            return
        }
        items.forEachIndexed { index, item ->
            val card = surface()
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label("${index + 1}", 24f, true, green), LinearLayout.LayoutParams(dp(44), -2))
            val texts = vertical().apply {
                addView(label(item.name, 16f, true))
                addView(label(getString(R.string.top_purchases, item.purchases), 13f, false, muted))
            }
            top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(label("›", 28f, true, green))
            card.addView(top)
            card.addView(label("${getString(R.string.top_spent)}: ${money(item.spentMilli)}   ·   " +
                "${getString(R.string.top_quantity)}: ${decimal(item.quantityMilli)} ${item.unit}", 12f, false, muted))
            card.contentDescription = "${item.name}, ${getString(R.string.top_purchases, item.purchases)}"
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { selected = item; renderDetail(item) }
            list.addView(card, margin())
        }
    }

    private fun renderDetail(item: FrequentItem) {
        val body = page(item.name, getString(R.string.top_detail))
        val back = Button(this).apply {
            text = getString(if (directlyOpened) R.string.scan_back else R.string.top_back)
            isAllCaps = false
            setTextColor(green)
            background = shape(Color.WHITE, 14)
            setOnClickListener {
                if (directlyOpened) finish() else { selected = null; renderList() }
            }
        }
        body.addView(back, margin())
        val summary = surface().apply {
            addView(label(getString(R.string.top_purchases, item.purchases), 20f, true))
            addView(label("${getString(R.string.top_spent)}: ${money(item.spentMilli)}", 15f))
            addView(label("${getString(R.string.top_quantity)}: ${decimal(item.quantityMilli)} ${item.unit}", 14f, false, muted))
        }
        body.addView(summary, margin())
        val points = history.series(item.id)
        val chartCard = surface()
        chartCard.addView(label(getString(R.string.top_price_unit, item.unit), 19f, true))
        chartCard.addView(PriceChart(points, item.historicalUnitMilli),
            LinearLayout.LayoutParams(-1, dp(245)).apply { topMargin = dp(18) })
        if (points.size == 1) chartCard.addView(label(getString(R.string.top_one_point), 12f, false, muted))
        if (points.isNotEmpty()) {
            chartCard.addView(label("${date(points.first().registeredMs)}  ·  ${money(points.first().unitPriceMilli)} / ${item.unit}", 13f))
            if (points.size > 1) chartCard.addView(label("${date(points.last().registeredMs)}  ·  ${money(points.last().unitPriceMilli)} / ${item.unit}", 13f))
        }
        if (item.historicalUnitMilli != null) chartCard.addView(label(
            "${getString(R.string.top_historic)}: ${money(item.historicalUnitMilli)} / ${item.unit}", 13f, false, green))
        body.addView(chartCard, margin())
    }

    /** 2003 apareix a l'esquerra com a punt separat; l'espai temporal de 23 anys no es dibuixa a escala. */
    private inner class PriceChart(
        private val values: List<PricePoint>, private val historic: Long?
    ) : View(this@TopPurchasesActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = getString(R.string.top_graph_accessibility, values.size) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (values.isEmpty() && historic == null) return
            val left = dp(54).toFloat()
            val right = width - dp(14).toFloat()
            val top = dp(16).toFloat()
            val bottom = height - dp(35).toFloat()
            val prices = values.map { it.unitPriceMilli.toDouble() } + listOfNotNull(historic?.toDouble())
            val low = (prices.minOrNull() ?: 0.0).coerceAtMost(0.0)
            val high = max(1.0, (prices.maxOrNull() ?: 1.0) * 1.15)
            fun y(price: Double) = (bottom - ((price - low) / (high - low) * (bottom - top))).toFloat()
            val purchaseLeft = if (historic != null) left + dp(56) else left
            val first = values.firstOrNull()?.registeredMs ?: 0L
            val last = values.lastOrNull()?.registeredMs ?: 0L
            fun x(index: Int, p: PricePoint): Float = if (values.size == 1) (purchaseLeft + right) / 2f
                else if (first == last) purchaseLeft + (right - purchaseLeft) * index / (values.size - 1)
                else purchaseLeft + (right - purchaseLeft) * ((p.registeredMs - first).toDouble() / (last - first)).toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = Color.rgb(221, 231, 223)
            paint.pathEffect = null
            repeat(4) { i ->
                val py = top + (bottom - top) * i / 3
                canvas.drawLine(left, py, right, py, paint)
                paint.style = Paint.Style.FILL
                paint.color = muted
                paint.textSize = dp(10).toFloat()
                canvas.drawText(money((high * (1 - i / 3.0)).toLong()), dp(2).toFloat(), py + dp(4), paint)
                paint.style = Paint.Style.STROKE
                paint.color = Color.rgb(221, 231, 223)
            }
            if (historic != null) {
                val hx = left + dp(9)
                val hy = y(historic.toDouble())
                paint.color = Color.rgb(183, 116, 54)
                paint.strokeWidth = dp(2).toFloat()
                paint.style = Paint.Style.STROKE
                paint.pathEffect = DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(4).toFloat()), 0f)
                if (values.isNotEmpty()) canvas.drawLine(hx, hy, x(0, values.first()),
                    y(values.first().unitPriceMilli.toDouble()), paint)
                paint.pathEffect = null
                paint.style = Paint.Style.FILL
                canvas.drawCircle(hx, hy, dp(5).toFloat(), paint)
                paint.textSize = dp(10).toFloat()
                canvas.drawText("2003", left, height - dp(9).toFloat(), paint)
            }
            if (values.isNotEmpty()) {
                val path = Path()
                values.forEachIndexed { index, point ->
                    val px = x(index, point)
                    val py = y(point.unitPriceMilli.toDouble())
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                paint.color = green
                paint.strokeWidth = dp(3).toFloat()
                paint.style = Paint.Style.STROKE
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                values.forEachIndexed { index, point ->
                    canvas.drawCircle(x(index, point), y(point.unitPriceMilli.toDouble()), dp(4).toFloat(), paint)
                }
                paint.color = muted
                paint.textSize = dp(10).toFloat()
                val firstLabel = date(first)
                canvas.drawText(firstLabel, purchaseLeft, height - dp(9).toFloat(), paint)
                if (values.size > 1) {
                    val endLabel = date(last)
                    canvas.drawText(endLabel, right - paint.measureText(endLabel), height - dp(9).toFloat(), paint)
                }
            }
        }
    }
}
