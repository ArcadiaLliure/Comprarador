package com.comprarador.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.math.BigDecimal
import java.math.RoundingMode

/** Imports en mil·lèsimes d'euro; quantitats en mil·lèsimes d'unitat. */
data class PurchasedItem(
    val description: String,
    val currentMilli: Long,
    val quantityMilli: Long,
    val unit: String,
    val historicalId: Long? = null,
    val historicalUnitMilli: Long? = null
)

data class FrequentItem(
    val id: Long,
    val name: String,
    val unit: String,
    val purchases: Int,
    val quantityMilli: Long,
    val spentMilli: Long,
    val lastRegisteredMs: Long,
    val historicalUnitMilli: Long?
)

data class PricePoint(val registeredMs: Long, val unitPriceMilli: Long)

/**
 * Base privada separada del catàleg immutable i de la guardiola existent.
 * No es fan peticions de xarxa. Les dades antigues de la guardiola només tenen totals:
 * no es poden reconstruir les línies dels tiquets anteriors.
 */
class PurchaseHistory(context: Context) : SQLiteOpenHelper(context, "purchase_history.sqlite", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE receipts (
            fingerprint TEXT PRIMARY KEY NOT NULL,
            registered_ms INTEGER NOT NULL CHECK(registered_ms >= 0)
        )""")
        db.execSQL("""CREATE TABLE products (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            product_key TEXT NOT NULL UNIQUE,
            display_name TEXT NOT NULL,
            normalized_name TEXT NOT NULL,
            unit TEXT NOT NULL CHECK(unit IN ('ud', 'kg', 'l')),
            historical_id INTEGER,
            historical_unit_milli INTEGER
        )""")
        db.execSQL("""CREATE TABLE receipt_lines (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            receipt_fingerprint TEXT NOT NULL REFERENCES receipts(fingerprint) ON DELETE CASCADE,
            line_number INTEGER NOT NULL,
            product_id INTEGER NOT NULL REFERENCES products(id),
            original_description TEXT NOT NULL,
            current_milli INTEGER NOT NULL CHECK(current_milli >= 0),
            quantity_milli INTEGER NOT NULL CHECK(quantity_milli > 0),
            UNIQUE(receipt_fingerprint, line_number)
        )""")
        db.execSQL("CREATE INDEX idx_receipts_registered ON receipts(registered_ms)")
        db.execSQL("CREATE INDEX idx_lines_product ON receipt_lines(product_id)")
        db.execSQL("CREATE INDEX idx_products_normalized ON products(normalized_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Cal una migració explícita: $oldVersion → $newVersion")
    }

    /** Inserció transaccional i idempotent: una relectura no duplica el rànquing. */
    fun record(fingerprint: String, registeredMs: Long, lines: List<PurchasedItem>): Boolean {
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        require(registeredMs >= 0 && lines.isNotEmpty())
        require(lines.all { it.description.isNotBlank() && it.currentMilli >= 0 &&
            it.quantityMilli > 0 && it.unit in setOf("ud", "kg", "l") })
        val db = writableDatabase
        db.beginTransaction()
        try {
            val receipt = ContentValues().apply {
                put("fingerprint", fingerprint)
                put("registered_ms", registeredMs)
            }
            if (db.insertWithOnConflict("receipts", null, receipt, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                db.setTransactionSuccessful()
                return false
            }
            lines.forEachIndexed { index, line ->
                val normalized = ReceiptParser.normalized(line.description).ifBlank { line.description.lowercase() }
                val key = if (line.historicalId != null) "hist:${line.historicalId}:${line.unit}"
                          else "text:$normalized:${line.unit}"
                val product = ContentValues().apply {
                    put("product_key", key)
                    put("display_name", line.description.trim())
                    put("normalized_name", normalized)
                    put("unit", line.unit)
                    if (line.historicalId != null) put("historical_id", line.historicalId)
                    if (line.historicalUnitMilli != null) put("historical_unit_milli", line.historicalUnitMilli)
                }
                db.insertWithOnConflict("products", null, product, SQLiteDatabase.CONFLICT_IGNORE)
                val id = db.rawQuery("SELECT id FROM products WHERE product_key=?", arrayOf(key)).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
                val values = ContentValues().apply {
                    put("receipt_fingerprint", fingerprint)
                    put("line_number", index)
                    put("product_id", id)
                    put("original_description", line.description.trim())
                    put("current_milli", line.currentMilli)
                    put("quantity_milli", line.quantityMilli)
                }
                check(db.insertOrThrow("receipt_lines", null, values) != -1L)
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Ordenació per nombre de tiquets diferents; no per despesa ni per quantitat. */
    fun ranking(search: String = "", period: String = "all", now: Long = System.currentTimeMillis()): List<FrequentItem> {
        val (start, end) = SavingsPeriods.boundaries(now, period)
        val text = ReceiptParser.normalized(search).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_")
        val sql = """SELECT p.id, p.display_name, p.unit, COUNT(DISTINCT l.receipt_fingerprint),
                SUM(l.quantity_milli), SUM(l.current_milli), MAX(r.registered_ms), p.historical_unit_milli
            FROM products p
            JOIN receipt_lines l ON l.product_id=p.id
            JOIN receipts r ON r.fingerprint=l.receipt_fingerprint
            WHERE r.registered_ms>=? AND r.registered_ms<? AND p.normalized_name LIKE ? ESCAPE '\'
            GROUP BY p.id
            ORDER BY COUNT(DISTINCT l.receipt_fingerprint) DESC, SUM(l.quantity_milli) DESC, p.display_name COLLATE NOCASE
            LIMIT 1000"""
        return readableDatabase.rawQuery(sql, arrayOf(start.toString(), end.toString(), "%$text%")).use { c ->
            buildList {
                while (c.moveToNext()) add(FrequentItem(
                    c.getLong(0), c.getString(1), c.getString(2), c.getInt(3),
                    c.getLong(4), c.getLong(5), c.getLong(6),
                    if (c.isNull(7)) null else c.getLong(7)
                ))
            }
        }
    }

    /** Un punt per tiquet; preu real per unitat, no preu del paquet. */
    fun series(productId: Long): List<PricePoint> {
        val sql = """SELECT r.registered_ms, SUM(l.current_milli), SUM(l.quantity_milli)
            FROM receipt_lines l JOIN receipts r ON r.fingerprint=l.receipt_fingerprint
            WHERE l.product_id=? GROUP BY l.receipt_fingerprint ORDER BY r.registered_ms ASC, r.fingerprint ASC"""
        return readableDatabase.rawQuery(sql, arrayOf(productId.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val quantity = c.getLong(2)
                    if (quantity <= 0L) continue
                    val unitPrice = BigDecimal(c.getLong(1)).multiply(BigDecimal(1000))
                        .divide(BigDecimal(quantity), 0, RoundingMode.HALF_UP).longValueExact()
                    add(PricePoint(c.getLong(0), unitPrice))
                }
            }
        }
    }
}
