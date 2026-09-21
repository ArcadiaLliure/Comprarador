package com.comprarador.app

/** Segment amb les coordenades de la línia retornada pel reconeixement local. */
data class ReceiptTextSegment(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * ML Kit pot retornar els blocs de text en un ordre diferent de l'ordre de lectura.
 * Recuperem les files segons la seva posició a la fotografia abans de passar-les al parser.
 * No afegim paraules ni imports que el reconeixement no hagi llegit.
 */
object ReceiptReadingOrder {
    fun reconstruct(segments: List<ReceiptTextSegment>): String {
        val sorted = segments.filter { it.text.isNotBlank() && it.bottom > it.top }
            .sortedWith(compareBy<ReceiptTextSegment> { (it.top + it.bottom) / 2 }.thenBy { it.left })
        if (sorted.isEmpty()) return ""
        data class Row(val items: MutableList<ReceiptTextSegment>, var top: Int, var bottom: Int)
        val rows = mutableListOf<Row>()
        for (segment in sorted) {
            val row = rows.lastOrNull()
            val overlap = if (row == null) 0 else minOf(segment.bottom, row.bottom) - maxOf(segment.top, row.top)
            val shorterHeight = if (row == null) 0 else minOf(segment.bottom - segment.top, row.bottom - row.top)
            if (row != null && overlap > 0 && overlap * 2 >= shorterHeight) {
                row.items += segment
                row.top = maxOf(row.top, segment.top)
                row.bottom = minOf(row.bottom, segment.bottom)
            } else {
                rows += Row(mutableListOf(segment), segment.top, segment.bottom)
            }
        }
        return rows.joinToString("\n") { row ->
            row.items.sortedBy { it.left }.joinToString(" ") { it.text.trim() }
        }
    }

    fun select(raw: String, geometric: String): Pair<String, ReceiptParseResult> {
        val original = ReceiptParser.parseDetailed(raw)
        if (geometric.isBlank()) return raw to original
        val rebuilt = ReceiptParser.parseDetailed(geometric)
        fun complete(r: ReceiptParseResult): Boolean =
            r.declaredItemCount != null && r.declaredTotalMilli != null &&
                !r.countMismatch && !r.totalMismatch
        return if (complete(rebuilt) && !complete(original) ||
            !complete(original) && rebuilt.lines.size > original.lines.size) geometric to rebuilt
        else raw to original
    }
}
