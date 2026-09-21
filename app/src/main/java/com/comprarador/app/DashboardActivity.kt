package com.comprarador.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.NumberFormat
import java.util.Locale

/** Portada local sense publicitat ni rastrejadors. */
class DashboardActivity : ComponentActivity() {
    private val forest = Color.rgb(28, 104, 75)
    private val ink = Color.rgb(26, 52, 44)
    private val muted = Color.rgb(105, 122, 114)
    private val paper = Color.rgb(246, 248, 244)
    private lateinit var wallet: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun params(bottom: Int = 14) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = forest
        window.navigationBarColor = paper
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(paper) }
        val content = column().apply { setPadding(dp(18), 0, dp(18), dp(28)) }
        val header = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(forest, Color.rgb(19, 77, 66))).apply { cornerRadius = dp(24).toFloat() }
            setPadding(dp(23), dp(26), dp(23), dp(25))
        }
        header.addView(text(getString(R.string.app_name), 35f, Color.WHITE, true))
        header.addView(text(getString(R.string.home_subtitle), 15f, Color.rgb(223, 240, 225)))
        content.addView(header, params(20))
        val balance = column().apply {
            background = shape(Color.WHITE)
            setPadding(dp(20), dp(18), dp(20), dp(17))
            elevation = dp(3).toFloat()
        }
        balance.addView(text(getString(R.string.piggy_title), 16f, muted, true))
        wallet = text("", 31f, forest, true)
        balance.addView(wallet)
        balance.addView(text(getString(R.string.home_piggy_note), 12f, muted))
        content.addView(balance, params(19))
        content.addView(tile("📷", getString(R.string.home_scan), getString(R.string.home_scan_desc)) {
            startActivity(Intent(this, ScanReceiptActivity::class.java))
        }, params())
        content.addView(tile("▥", getString(R.string.top_title), getString(R.string.home_top_desc)) {
            startActivity(Intent(this, TopPurchasesActivity::class.java))
        }, params())
        content.addView(text(getString(R.string.home_privacy), 12f, muted), params(8))
        scroll.addView(content)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::wallet.isInitialized) {
            try {
                val ledger = SavingsLedger(this)
                val total = ledger.total("all")
                ledger.close()
                val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
                wallet.text = format.format(total.amountMilli / 1000.0)
                wallet.setTextColor(if (total.amountMilli < 0) Color.rgb(169, 65, 52) else forest)
                wallet.contentDescription = getString(R.string.home_balance_accessibility, wallet.text)
            } catch (_: Exception) {
                wallet.text = getString(R.string.home_balance_unavailable)
            }
        }
    }

    private fun tile(symbol: String, title: String, subtitle: String, action: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.WHITE)
            elevation = dp(2).toFloat()
            setPadding(dp(17), dp(20), dp(14), dp(20))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        card.addView(text(symbol, 28f, forest), LinearLayout.LayoutParams(dp(53), -2))
        val words = column().apply {
            addView(text(title, 19f, ink, true))
            addView(text(subtitle, 13f, muted))
        }
        card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(text("›", 28f, forest))
        card.contentDescription = "$title. $subtitle"
        return card
    }
}
