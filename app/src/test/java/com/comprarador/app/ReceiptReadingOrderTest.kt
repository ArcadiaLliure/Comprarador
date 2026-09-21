package com.comprarador.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptReadingOrderTest {
    @Test fun reconstrueixFilesQuanMlKitSeparaLesColumnesIDesordenaElsBlocs() {
        val fragments = listOf(
            ReceiptTextSegment("DESGLOS DE L'IVA", 0, 98, 210, 116),
            ReceiptTextSegment("1,39", 390, 76, 440, 94),
            ReceiptTextSegment("1,24", 390, 54, 440, 72),
            ReceiptTextSegment("0,15", 390, 10, 440, 28),
            ReceiptTextSegment("PATATA GRANEL", 0, 32, 180, 50),
            ReceiptTextSegment("0,650 Kg. X1,90", 90, 54, 250, 72),
            ReceiptTextSegment("ARTICLES 2 TOTAL EUROS", 0, 76, 255, 94),
            ReceiptTextSegment("1Un. X BOSSA REUTILITZABLE G12", 0, 10, 350, 28),
            ReceiptTextSegment("7,95 10,00 0,79 8,74", 0, 120, 300, 138)
        )
        val geometric = ReceiptReadingOrder.reconstruct(fragments)
        val raw = fragments.joinToString("\n") { it.text }
        assertTrue(ReceiptParser.parseDetailed(raw).lines.size < 2)
        val (chosen, parsed) = ReceiptReadingOrder.select(raw, geometric)
        assertEquals(geometric, chosen)
        assertEquals(2, parsed.lines.size)
        assertEquals(2, parsed.declaredItemCount)
        assertEquals(1390L, parsed.declaredTotalMilli)
        assertEquals(1390L, parsed.parsedTotalMilli)
        assertFalse(parsed.countMismatch)
        assertFalse(parsed.totalMismatch)
        assertEquals("PATATA GRANEL", parsed.lines[1].description)
        assertEquals(650L, parsed.lines[1].quantityMilli)
    }

    @Test fun noInventaProductesSiLOcrNoHaLlegitLaResta() {
        val raw = "BOSSA REUTILITZABLE G12 0,15"
        val (chosen, parsed) = ReceiptReadingOrder.select(raw, "")
        assertEquals(raw, chosen)
        assertEquals(1, parsed.lines.size)
        assertEquals(null, parsed.declaredTotalMilli)
        assertEquals(null, parsed.declaredItemCount)
    }
}
