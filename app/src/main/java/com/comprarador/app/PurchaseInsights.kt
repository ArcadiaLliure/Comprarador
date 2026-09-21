package com.comprarador.app

import android.database.sqlite.SQLiteDatabase
import java.math.BigDecimal
import java.math.RoundingMode

/** Consultes de lectura sobre l'historial privat. No consulta el catàleg del 2003. */
class PurchaseInsights(private val db: SQLiteDatabase) {
    data class PreviousPurchase(val productId: Long, val registeredMs: Long, val unitPriceMilli: Long)

    /**
     * Una equivalència confirmada identifica el producte pel seu identificador històric.
     * Si encara no n'hi ha, exigim el mateix nom normalitzat i la mateixa unitat.
     * Agrupem línies del mateix tiquet per calcular el preu real per unitat.
     */
    fun previous(description: String, unit: String, historicalId: Long?): PreviousPurchase? {
        val normalized = ReceiptParser.normalized(description)
        if (normalized.isBlank() || unit !in setOf("ud", "kg", "l")) return null
        val predicate = if (historicalId != null) "p.product_key=?" else "p.normalized_name=? AND p.unit=?"
        val args = if (historicalId != null) arrayOf("hist:$historicalId:$unit") else arrayOf(normalized, unit)
        val query = """SELECT p.id,r.registered_ms,SUM(l.current_milli),SUM(l.quantity_milli)
            FROM products p JOIN receipt_lines l ON l.product_id=p.id
            JOIN receipts r ON r.fingerprint=l.receipt_fingerprint
            WHERE $predicate GROUP BY p.id,l.receipt_fingerprint
            ORDER BY r.registered_ms DESC,r.fingerprint DESC LIMIT 1"""
        return db.rawQuery(query, args).use { c ->
            if (!c.moveToFirst() || c.getLong(3) <= 0L) null
            else PreviousPurchase(c.getLong(0), c.getLong(1), unitPrice(c.getLong(2), c.getLong(3)))
        }
    }

    /** Cerca sense el límit de 1.000 articles del rànquing; permet obrir el mateix detall des de l'escaneig. */
    fun item(productId: Long): FrequentItem? {
        val query = """SELECT p.id,p.display_name,p.unit,COUNT(DISTINCT l.receipt_fingerprint),
            SUM(l.quantity_milli),SUM(l.current_milli),MAX(r.registered_ms),p.historical_unit_milli
            FROM products p JOIN receipt_lines l ON l.product_id=p.id
            JOIN receipts r ON r.fingerprint=l.receipt_fingerprint
            WHERE p.id=? GROUP BY p.id"""
        return db.rawQuery(query, arrayOf(productId.toString())).use { c ->
            if (!c.moveToFirst()) null else FrequentItem(
                c.getLong(0), c.getString(1), c.getString(2), c.getInt(3),
                c.getLong(4), c.getLong(5), c.getLong(6),
                if (c.isNull(7)) null else c.getLong(7)
            )
        }
    }

    companion object {
        fun unitPrice(totalMilli: Long, quantityMilli: Long): Long {
            require(totalMilli >= 0L && quantityMilli > 0L)
            return BigDecimal(totalMilli).multiply(BigDecimal(1000))
                .divide(BigDecimal(quantityMilli), 0, RoundingMode.HALF_UP).longValueExact()
        }

        /** Diferència del preu unitari actual respecte de la darrera compra, no del 2003. */
        fun variation(currentUnitMilli: Long, previousUnitMilli: Long): Long =
            Math.subtractExact(currentUnitMilli, previousUnitMilli)
    }
}
