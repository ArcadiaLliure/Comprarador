package com.comprarador.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import java.util.UUID

/** Registre immutable, separat del catàleg històric de només lectura. */
data class SavingEvent(
    val fingerprint: String,
    val amountMilli: Long,
    val historicalMilli: Long,
    val currentMilli: Long,
    val matchedLines: Int,
    val totalLines: Int,
    val coveragePermille: Int,
    val createdAtMs: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString()
)

data class SavingTotal(val amountMilli: Long, val purchases: Int)

/** Límits del calendari local: la setmana comença dilluns i el final és exclusiu. */
object SavingsPeriods {
    fun boundaries(now: Long, period: String): Pair<Long, Long> {
        if (period == "all") return 0L to Long.MAX_VALUE
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (period) {
            "day" -> Unit
            "week" -> {
                val daysSinceMonday = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7
                start.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
            }
            "month" -> start.set(Calendar.DAY_OF_MONTH, 1)
            "year" -> {
                start.set(Calendar.MONTH, Calendar.JANUARY)
                start.set(Calendar.DAY_OF_MONTH, 1)
            }
            else -> throw IllegalArgumentException("Període desconegut: $period")
        }
        val end = (start.clone() as Calendar).apply {
            when (period) {
                "day" -> add(Calendar.DAY_OF_MONTH, 1)
                "week" -> add(Calendar.DAY_OF_MONTH, 7)
                "month" -> add(Calendar.MONTH, 1)
                "year" -> add(Calendar.YEAR, 1)
            }
        }
        return start.timeInMillis to end.timeInMillis
    }
}

class SavingsLedger(context: Context) : SQLiteOpenHelper(context, "savings.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE saving_events (
                id TEXT PRIMARY KEY NOT NULL,
                receipt_fingerprint TEXT UNIQUE NOT NULL,
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                amount_milli INTEGER NOT NULL,
                historical_milli INTEGER NOT NULL CHECK(historical_milli >= 0),
                current_milli INTEGER NOT NULL CHECK(current_milli >= 0),
                matched_lines INTEGER NOT NULL CHECK(matched_lines > 0),
                total_lines INTEGER NOT NULL CHECK(total_lines >= matched_lines),
                coverage_permille INTEGER NOT NULL CHECK(coverage_permille BETWEEN 0 AND 1000),
                basis TEXT NOT NULL CHECK(basis = 'catalog_2003_nominal')
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_savings_created_at ON saving_events(created_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Les futures versions han de migrar les dades: no suprimiu mai la taula.
        throw IllegalStateException("Migració de la guardiola no admesa $oldVersion -> $newVersion")
    }

    /** La restricció UNIQUE evita duplicar un mateix tiquet, encara que es torni a escanejar un altre dia. */
    fun record(event: SavingEvent): Boolean {
        require(Math.subtractExact(event.historicalMilli, event.currentMilli) == event.amountMilli)
        require(event.historicalMilli >= 0 && event.currentMilli >= 0)
        require(event.coveragePermille in 0..1000 && event.totalLines >= event.matchedLines && event.matchedLines > 0)
        val values = ContentValues().apply {
            put("id", event.id)
            put("receipt_fingerprint", event.fingerprint)
            put("created_at_ms", event.createdAtMs)
            put("amount_milli", event.amountMilli)
            put("historical_milli", event.historicalMilli)
            put("current_milli", event.currentMilli)
            put("matched_lines", event.matchedLines)
            put("total_lines", event.totalLines)
            put("coverage_permille", event.coveragePermille)
            put("basis", "catalog_2003_nominal")
        }
        return writableDatabase.insertWithOnConflict(
            "saving_events", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun total(period: String, now: Long = System.currentTimeMillis()): SavingTotal {
        val (start, end) = SavingsPeriods.boundaries(now, period)
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount_milli), 0), COUNT(*) FROM saving_events " +
                "WHERE created_at_ms >= ? AND created_at_ms < ?",
            arrayOf(start.toString(), end.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            return SavingTotal(cursor.getLong(0), cursor.getInt(1))
        }
    }
}
