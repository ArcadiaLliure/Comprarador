package com.comprarador.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transcripció anonimitzada d'un tiquet real, sense dades del comerç ni identificadors. */
class ReceiptParserTest {
    private val ticket = """
        1Un. X BOSSA REUTILITZABLE G12 0,15
        PATATA GRANEL
        0,650 Kg. X1,90 1,24
        1Un. X GALETES DINOSAURUS 185G 1,79
        PRESSEC GROC
        0,228 Kg. X2,99 0,68
        1Un. X GALETES GULLON FINES XO 2,65
        1Un. X GALETES GULLON FIBRA ZE 2,05
        1Un. X PA DE MOTLLE BIMBO S/CR 2,99
        1Un. X PERNIL ROSTIT FORN ARGA 2,25
        ------------------------------------
        Artícles 8 TOTAL EUROS 13,80
        TARGETA DE CRÈDIT 13,80
        DESGLOS DE L'IVA
        BASE % IVA I.IVA IMP.
        4,72 4,00 0,19 4,91
        7,95 10,00 0,79 8,74
        0,12 21,00 0,03 0,15
    """.trimIndent()

    @Test fun separatesProductsFromTaxAndReconcilesTotals() {
        val result = ReceiptParser.parseDetailed(ticket)
        assertEquals(8, result.lines.size)
        assertEquals(8, result.declaredItemCount)
        assertEquals(13800L, result.declaredTotalMilli)
        assertEquals(13800L, result.parsedTotalMilli)
        assertFalse(result.countMismatch)
        assertFalse(result.totalMismatch)
        assertTrue(result.lines.none { it.description == "7,95" || it.description == "0,12" })
    }

    @Test fun attachesWeightRowsToPrecedingProductsButKeepsPackAsUnit() {
        val lines = ReceiptParser.parse(ticket)
        assertEquals("PATATA GRANEL", lines[1].description)
        assertEquals(650L, lines[1].quantityMilli)
        assertEquals("kg", lines[1].quantityUnit)
        assertEquals("PRESSEC GROC", lines[3].description)
        assertEquals(228L, lines[3].quantityMilli)
        assertEquals("kg", lines[3].quantityUnit)
        assertEquals("ud", lines[2].quantityUnit)
        assertEquals(1000L, lines[2].quantityMilli)
    }

    @Test fun identifiesMissingProductInsteadOfClaimingReceiptComplete() {
        val incomplete = ReceiptParser.parseDetailed(ticket.replace("1Un. X PERNIL ROSTIT FORN ARGA 2,25", ""))
        assertEquals(7, incomplete.lines.size)
        assertTrue(incomplete.countMismatch)
        assertTrue(incomplete.totalMismatch)
    }

    @Test fun handlesWeightAndProductOnSameOcrLine() {
        val merged = ticket.replace("PATATA GRANEL\n0,650 Kg. X1,90 1,24", "PATATA GRANEL 0,650 Kg. X1,90 1,24")
        val result = ReceiptParser.parseDetailed(merged)
        assertEquals(8, result.lines.size)
        assertEquals(650L, result.lines[1].quantityMilli)
        assertFalse(result.totalMismatch)
    }

    @Test fun taxRowsWithoutSummaryAreNotProducts() {
        val result = ReceiptParser.parse("7,95 10,00 0,79 8,74\n0,12 21,00 0,03 0,15")
        assertTrue(result.isEmpty())
    }
}
