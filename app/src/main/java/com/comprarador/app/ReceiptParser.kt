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

/** Resultat auditable: evita presentar com a completa una lectura OCR parcial. */
data class ReceiptParseResult(
    val lines: List<ReceiptLine>,
    val declaredItemCount: Int?,
    val declaredTotalMilli: Long?
) {
    val parsedTotalMilli: Long get() = lines.fold(0L) { total, item -> Math.addExact(total, item.currentMilli) }
    val countMismatch: Boolean get() = declaredItemCount != null && declaredItemCount != lines.size
    val totalMismatch: Boolean get() = declaredTotalMilli != null && declaredTotalMilli != parsedTotalMilli
}

/**
 * Parser de tiquets, no de files genèriques amb dos decimals.
 * Admet productes de diverses línies, però descarta el desglossament fiscal i les línies
 * de pes orfes: mai no inventem el nom d'un producte ni un import que no consti a l'OCR.
 */
object ReceiptParser {
    private val spaces = Regex("\\s+")
    private val totalWords = Regex("(?iu)^(?:TOTAL|SUBTOTAL|IMPORTE|CANVI|CAMBIO|EFECTIU|EFECTIVO|TARGETA|TARJETA|IVA|DESCOMPTE|DESCUENTO|DTO|AHORRO|ESTALVI|REDONDEO|CUPON|CUPÓ|PAGO|PAGAMENT|VISA|MASTERCARD)\\b")
    private val receiptTotal = Regex("(?iu)^ART[IÍ]CLES?\\s+(\\d{1,3})\\s+TOTAL(?:\\s+EUROS?)?\\s+(\\d{1,6}[.,]\\d{2})\\b")
    private val endOfItems = Regex("(?iu)^(?:ART[IÍ]CLES?\\s+\\d+\\s+TOTAL|TOTAL(?:\\s+EUROS?)?\\b|SUBTOTAL\\b|TARGETA\\b|TARJETA\\b|DESGLOS(?:SAMENT)?\\b|DESGLOSE\\b|DESGLOSSAMENT\\b|BASE\\s+(?:%\\s*)?IVA\\b|IVA\\b|FORMA\\s+DE\\s+PAGAMENT\\b)")
    private val header = Regex("(?iu)^(?:NIF|CIF|FS|TEL|TELÈFON|TELEFONO|SPAR|GR[AÀ]CIES|GRACIAS|TOTS\\s+ELS\\s+PREUS|TODOS\\s+LOS\\s+PRECIOS|C\\.\\s+DE\\b)\\b")
    private val date = Regex("^\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b")
    private val separator = Regex("^[-_=·.]{4,}$")
    private val trailingAmount = Regex("^(.+?)\\s+(-?\\d{1,5}[.,]\\d{2})\\s*€?$")
    private val amountOnly = Regex("^(\\d{1,5}[.,]\\d{2})\\s*€?$")
    private val itemPrefix = Regex("(?iu)^\\s*(\\d{1,3})\\s*(?:un|ud|u)\\.?\\s*(?:[x×]\\s*)?")
    // En tiquets SPAR, el preu per kg és una línia pròpia sota el nom: 0,650 Kg. X1,90 1,24.
    private val weightLine = Regex("(?iu)^\\s*(\\d+(?:[.,]\\d{1,3})?)\\s*(kg|g|gr|l|cl|ml)\\.?\\s*[x×*]\\s*(\\d{1,5}[.,]\\d{2})\\s+(\\d{1,5}[.,]\\d{2})\\s*€?$")
    // 185G en un nom comercial és el format de l'envàs, NO 0,185 kg comprats a granel.
    private val inlineLooseMeasure = Regex("(?iu)(?:^|\\s)(\\d+(?:[.,]\\d{1,3})?)\\s+(kg|l)\\.?$")
    private val inlineWeightLine = Regex("(?iu)^(.+?)\\s+(\\d+(?:[.,]\\d{1,3})?)\\s*(kg|g|gr|l|cl|ml)\\.?\\s*[x×*]\\s*(\\d{1,5}[.,]\\d{2})\\s+(\\d{1,5}[.,]\\d{2})\\s*€?$")
    private val hasLetters = Regex("\\p{L}")

    fun parse(text: String): List<ReceiptLine> = parseDetailed(text).lines

    fun parseDetailed(text: String): ReceiptParseResult {
        val products = mutableListOf<ReceiptLine>()
        var pendingName: String? = null
        var pendingCount = 1L
        var declaredCount: Int? = null
        var declaredTotal: Long? = null
        var sectionStarted = false

        for (raw in text.lineSequence()) {
            val line = raw.trim().replace(spaces, " ")
            if (line.isEmpty() || separator.matches(line)) continue
            val total = receiptTotal.find(line)
            if (total != null) {
                declaredCount = total.groupValues[1].toIntOrNull()
                declaredTotal = runCatching { moneyMilli(total.groupValues[2]) }.getOrNull()
                break
            }
            if (endOfItems.containsMatchIn(line) || totalWords.containsMatchIn(line) && sectionStarted) break
            if (header.containsMatchIn(line) || date.containsMatchIn(line)) continue

            // Les línies de pes només són vàlides si abans hem llegit un nom sense import.
            val weighed = weightLine.matchEntire(line)
            if (weighed != null) {
                val name = pendingName
                if (name != null) {
                    val amount = runCatching { moneyMilli(weighed.groupValues[4]) }.getOrNull()
                    val quantity = measureMilli(weighed.groupValues[1], weighed.groupValues[2])
                    if (amount != null && amount > 0 && quantity != null && quantity > 0) {
                        products += ReceiptLine(name, amount, quantity, canonicalUnit(weighed.groupValues[2]))
                        sectionStarted = true
                    }
                }
                pendingName = null
                pendingCount = 1
                continue
            }

            // L'OCR també pot ajuntar el nom amb la línia de pes.
            val inlineWeighed = inlineWeightLine.matchEntire(line)
            if (inlineWeighed != null) {
                val (name, _) = stripItemPrefix(inlineWeighed.groupValues[1])
                val amount = runCatching { moneyMilli(inlineWeighed.groupValues[5]) }.getOrNull()
                val quantity = measureMilli(inlineWeighed.groupValues[2], inlineWeighed.groupValues[3])
                if (validProductName(name) && amount != null && amount > 0 && quantity != null && quantity > 0) {
                    products += ReceiptLine(name, amount, quantity, canonicalUnit(inlineWeighed.groupValues[3]))
                    sectionStarted = true
                }
                pendingName = null
                pendingCount = 1
                continue
            }

            val singlePrice = amountOnly.matchEntire(line)
            if (singlePrice != null) {
                val name = pendingName
                if (name != null) {
                    val amount = runCatching { moneyMilli(singlePrice.groupValues[1]) }.getOrNull()
                    if (amount != null && amount > 0) {
                        products += ReceiptLine(name, amount, pendingCount * 1000, "ud")
                        sectionStarted = true
                    }
                }
                pendingName = null
                pendingCount = 1
                continue
            }

            val priced = trailingAmount.matchEntire(line)
            if (priced != null) {
                val (name, count) = stripItemPrefix(priced.groupValues[1])
                if (!validProductName(name)) {
                    pendingName = null
                    continue
                }
                val amount = runCatching { moneyMilli(priced.groupValues[2]) }.getOrNull()
                if (amount != null && amount > 0) {
                    val loose = inlineLooseMeasure.find(name)
                    val quantity = if (loose != null) measureMilli(loose.groupValues[1], loose.groupValues[2]) else null
                    val unit = if (quantity != null && quantity > 0) canonicalUnit(loose!!.groupValues[2]) else "ud"
                    products += ReceiptLine(name, amount, if (quantity != null && quantity > 0) quantity else count * 1000, unit)
                    sectionStarted = true
                }
                pendingName = null
                pendingCount = 1
                continue
            }

            // Un nom sense preu pot correspondre a l'article a pes de la línia següent.
            // Només acceptem aquest estat quan ja s'ha començat la secció d'articles.
            val (name, count) = stripItemPrefix(line)
            if (sectionStarted && validProductName(name)) {
                pendingName = name
                pendingCount = count
            }
        }
        return ReceiptParseResult(products, declaredCount, declaredTotal)
    }

    private fun stripItemPrefix(value: String): Pair<String, Long> {
        val prefix = itemPrefix.find(value)
        val count = prefix?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        return (if (prefix == null) value else value.removeRange(prefix.range)).trim() to count
    }

    private fun validProductName(name: String): Boolean = name.length >= 3 &&
        hasLetters.containsMatchIn(name) &&
        !totalWords.containsMatchIn(name) &&
        !header.containsMatchIn(name) &&
        !endOfItems.containsMatchIn(name)

    private fun canonicalUnit(rawUnit: String) = when (rawUnit.lowercase(Locale.ROOT)) {
        "kg", "g", "gr" -> "kg"
        else -> "l"
    }

    private fun measureMilli(value: String, rawUnit: String): Long? = runCatching {
        val factor = when (rawUnit.lowercase(Locale.ROOT)) {
            "kg", "l" -> 1000L
            "cl" -> 10L
            else -> 1L
        }
        val result = BigDecimal(value.replace(',', '.'))
            .multiply(BigDecimal.valueOf(factor))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
        result.takeIf { it > 0L }
    }.getOrNull()

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
