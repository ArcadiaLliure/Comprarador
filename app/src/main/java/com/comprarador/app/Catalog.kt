package com.comprarador.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.min

data class HistoricalProduct(
    val id: Long,
    val retailer: String,
    val name: String,
    val brand: String,
    val packaging: String,
    val normalized: String,
    val priceMilli: Long,
    val quantityMilli: Long,
    val unit: String,
    val line: Int,
    val score: Double
) {
    fun historicalMilli(receiptQuantityMilli: Long): Long =
        BigDecimal(priceMilli).multiply(BigDecimal(receiptQuantityMilli))
            .divide(BigDecimal(quantityMilli),0,RoundingMode.HALF_UP).longValueExact()
}

class Catalog(context: Context) : AutoCloseable {
    private val db: SQLiteDatabase
    val size: Long
    init {
        val file = context.getDatabasePath("catalog.sqlite")
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            val temporary = File(file.parentFile, "catalog.sqlite.tmp")
            context.assets.open("catalog.sqlite").use { source ->
                temporary.outputStream().use { source.copyTo(it) }
            }
            check(temporary.renameTo(file)) { "No s’ha pogut instal·lar la base de dades" }
        }
        db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        size = db.rawQuery("SELECT count(*) FROM products", null).use { c ->
            check(c.moveToFirst())
            c.getLong(0)
        }
    }

    fun candidates(description: String, unit: String): List<HistoricalProduct> {
        val input = ReceiptParser.normalized(description).split(' ')
            .filter { it.length >= 3 && it.any(Char::isLetter) }.distinct()
        if (input.isEmpty()) return emptyList()
        val query = input.take(3).joinToString(" AND ") { "$it*" }
        val found = linkedMapOf<Long, HistoricalProduct>()
        fun collect(sql: String, args: Array<String>) {
            db.rawQuery(sql,args).use { cursor ->
                while (cursor.moveToNext()) {
                    val p = HistoricalProduct(
                        id=cursor.getLong(0), retailer=cursor.getString(1),
                        name=cursor.getString(2), brand=cursor.getString(3),
                        packaging=cursor.getString(4), normalized=cursor.getString(5),
                        priceMilli=cursor.getLong(6), quantityMilli=cursor.getLong(7),
                        unit=cursor.getString(8), line=cursor.getInt(9), score=0.0
                    )
                    if (!found.containsKey(p.id) && compatible(input,p.normalized.split(' '))) {
                        val score = similarity(input,p.normalized.split(' '))
                        if (score >= 0.18) found[p.id] = p.copy(score=score)
                    }
                }
            }
        }
        val columns = "SELECT p.id,p.retailer,p.name,p.brand,p.packaging,p.normalized_name,p.price_milli_eur,p.quantity_milli,p.quantity_unit,p.source_line FROM products p"
        val suffix = " AND p.quantity_unit=? AND p.quantity_milli IS NOT NULL LIMIT 400"
        try {
            collect("$columns JOIN products_fts f ON p.id=f.rowid WHERE f.normalized_name MATCH ?$suffix", arrayOf(query,unit))
        } catch (_: Exception) {
            // FTS4 pot no estar disponible en algunes variants d’Android.
        }
        if (found.size < 5) {
            val first = input.first().replace("%", "\\%").replace("_", "\\_")
            collect("$columns WHERE p.normalized_name LIKE ? ESCAPE '\\'$suffix", arrayOf("%$first%",unit))
        }
        return found.values.sortedWith(compareByDescending<HistoricalProduct> { it.score }.thenBy { it.priceMilli })
            .take(8)
    }

    private val exclusive = listOf(setOf("entera","semidesnatada","desnatada"),
        setOf("golden","fuji","braeburn","reineta","granny"),
        setOf("joven","crianza","reserva","roble"),
        setOf("ecologico","ecologica","eco"))

    private fun compatible(input: List<String>, historic: List<String>): Boolean = exclusive.all { group ->
        val a = input.filter { it in group }.toSet()
        val b = historic.filter { it in group }.toSet()
        a == b
    }

    private fun similarity(input: List<String>, historic: List<String>): Double {
        val a = input.filter { it.any(Char::isLetter) }.toSet()
        val b = historic.filter { it.any(Char::isLetter) }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        return (2.0 * intersection / (a.size + b.size))
    }

    override fun close() = db.close()
}
