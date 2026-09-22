package com.comprarador.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mostra real d'OCR, retallada de dades personals i comercials no necessàries. */
class ReceiptRealOcrTest {
    private val realOcr = """
        1UHUSSA REUTILITZABLE G12 0, 15
        PAiAIA GRANEL
        0,650 Kg. X1,90 1,24
        1Un. X GALETES DINOSAURUS 185G 1,79
        PRESSEC GROC
        0,228 Kg, X2,99 0,68
        1Un. X GALETES GULL ON FINES XO 2,65
        1un. X GALETES GULLON FIBRA ZE 2,05
        1Un. X PA DE MOTLLE BIMBO S/CR 2,99
        1Un, X PERNI. ROSTIT FORN ARGA 2,25
        Articles 8 TOTAL EUROS 13,80
        TARGETA DE CRÈDIT 13,80
        SGLOS DE L'TVA
        IVA I.IVA IMP.
        4,00 0,19 4.91
        10,00 0,79 8,74
        21.00 0,03 0,15
    """.trimIndent()

    @Test fun recuperaElsVuitArticlesDeLOcrReal() {
        val receipt = ReceiptParser.parseDetailed(realOcr)
        assertEquals(8, receipt.declaredItemCount)
        assertEquals(13800L, receipt.declaredTotalMilli)
        assertEquals(8, receipt.lines.size)
        assertEquals(13800L, receipt.parsedTotalMilli)
        assertFalse(receipt.countMismatch)
        assertFalse(receipt.totalMismatch)
        assertEquals(150L, receipt.lines[0].currentMilli)
        assertEquals(1240L, receipt.lines[1].currentMilli)
    }

    @Test fun vinculaElsPesosAmbElNomAnterior() {
        val receipt = ReceiptParser.parseDetailed(realOcr)
        val patata = receipt.lines[1]
        assertTrue(patata.description.contains("GRANEL"))
        assertEquals("kg", patata.quantityUnit)
        assertEquals(650L, patata.quantityMilli)
        val pressec = receipt.lines[3]
        assertEquals("PRESSEC GROC", pressec.description)
        assertEquals("kg", pressec.quantityUnit)
        assertEquals(228L, pressec.quantityMilli)
        assertEquals(680L, pressec.currentMilli)
        assertTrue(receipt.lines.none { it.description.startsWith("0,228") })
    }

    @Test fun noConfónLOcrFiscalAmbArticlesNiUnEnvàsAmbPesComprat() {
        val receipt = ReceiptParser.parseDetailed(realOcr)
        assertTrue(receipt.lines.none { it.description.contains("IVA") })
        assertEquals("ud", receipt.lines[2].quantityUnit)
        assertEquals(1000L, receipt.lines[2].quantityMilli)
    }

    @Test fun siElMotorNoHaLlegitLaBossaEsDetectaLaDiferència() {
        val incomplete = ReceiptParser.parseDetailed(realOcr.replace("1UHUSSA REUTILITZABLE G12 0, 15\n", ""))
        assertEquals(7, incomplete.lines.size)
        assertTrue(incomplete.countMismatch)
        assertTrue(incomplete.totalMismatch)
    }
}
