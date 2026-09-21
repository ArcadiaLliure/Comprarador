package com.comprarador.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

data class ReceiptLine(
    val description: String,
    val currentMilli: Long,
    val quantityMilli: Long,
    val quantityUnit: String
)

/** Les quantitats s’emmagatzemen en mil·lèsimes: 0,001 € i 0,001 kg/l/unitats. */
object ReceiptParser {
    private val totalWords = Regex("(?i)^(TOTAL|SUBTOTAL|IMPORTE|CAMBIO|EFECTIVO|TARJETA|IVA|DESCUENTO|DTO|AHORRO|REDONDEO|CUPON|PAGO|VISA|MASTERCARD)\\b")
    private val trailingAmount = Regex("(?i)^(.+?)\\s+(-?\\d{1,5}[.,]\\d{2})\\s*€?\\s*$")
    private val explicitMeasure = Regex("(?i)(?<![\\w/])([0-9]+(?:[.,][0-9]{1,3})?)\\s*(kg|g|gr|l|cl|ml|uds?\\.?|unid\\.?|unidades?)(?![\\w/])")
    private val bareWeight = Regex("(?i)^(.+?)\\s+(\\d+[.,]\\d{3})$")

    fun parse(text: String): List<ReceiptLine> = text.lines().mapNotNull { raw ->
        val line = raw.trim().replace(Regex("\\s+"), " ")
        if (line.isEmpty() || totalWords.containsMatchIn(line)) return@mapNotNull null
        val match = trailingAmount.matchEntire(line) ?: return@mapNotNull null
        var name = match.groupValues[1].trim()
        if (name.length < 3) return@mapNotNull null
        val cents = try { moneyMilli(match.groupValues[2]) } catch (_: Exception) { return@mapNotNull null }
        if (cents <= 0) return@mapNotNull null
        var unit = "ud"
        var quantity = 1000L
        val explicit = explicitMeasure.findAll(name).lastOrNull()
        if (explicit != null) {
            val n = explicit.groupValues[1].replace(',', '.').toBigDecimalOrNull()
            val rawUnit = explicit.groupValues[2].lowercase(Locale.ROOT).trimEnd('.')
            if (n != null && n > BigDecimal.ZERO) {
                val factor = when (rawUnit) {
                    "kg", "l" -> 1000
                    "g", "gr", "ml" -> 1
                    "cl" -> 10
                    else -> 1000
                }
                quantity = n.multiply(BigDecimal(factor)).setScale(0, RoundingMode.HALF_UP).toLong()
                unit = when (rawUnit) {
                    "kg", "g", "gr" -> "kg"
                    "l", "cl", "ml" -> "l"
                    else -> "ud"
                }
                // Conservem el nom comercial i el format per cercar equivalències.
            }
        } else {
            // No deduïm el pes de «1,250» sense unitat: podria ser un preu o un codi.
            val ambiguous = bareWeight.matchEntire(name)
            if (ambiguous != null) name = name.trim()
        }
        ReceiptLine(name, cents, quantity, unit)
    }

    fun moneyMilli(s: String): Long = BigDecimal(s.trim().replace(',', '.'))
        .multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun normalized(s: String): String {
        val ascii = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val aliases = mapOf("ecologica" to "ecologico", "eco" to "ecologico", "manz" to "manzana", "poma" to "manzana", "pomes" to "manzana",
            "manzanas" to "manzana", "platanos" to "platano", "platan" to "platano",
            "llet" to "leche", "semi" to "semidesnatada", "semides" to "semidesnatada",
            "ent" to "entera", "desnat" to "desnatada", "patates" to "patata",
            "tomaquet" to "tomate")
        val stop = setOf("de","del","la","el","en","para","y","con","sin","aprox",
            "unidad","unidades","unid","ud","u","bolsa","malla","botella","bandeja",
            "pack","envase","cal","calibre","unos","una","un","los","las","por","tipo")
        return Regex("[a-z0-9]+").findAll(ascii).map { aliases[it.value] ?: it.value }
            .filter { it !in stop }.joinToString(" ")
    }
}
