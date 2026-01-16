package ua.com.programmer.pick.core.scanner

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parser for GS1 barcodes (DataMatrix, GS1-128).
 * Extracts Application Identifiers (AIs) and their values.
 *
 * Common AIs:
 * - 01: GTIN (14 digits)
 * - 10: Batch/Lot number (variable length)
 * - 11: Production date (YYMMDD)
 * - 17: Expiration date (YYMMDD)
 * - 21: Serial number (variable length)
 * - 30: Variable count (variable length)
 * - 310x: Net weight in kg (6 digits, x = decimal places)
 */
@Singleton
class GS1Parser @Inject constructor() {

    companion object {
        private const val TAG = "GS1Parser"

        // GS1 Group Separator character
        private const val GS = '\u001D'
        private const val FNC1 = '\u00E8' // Sometimes used as FNC1

        // AI definitions: AI code -> (fixed length or -1 for variable, description)
        private val AI_DEFINITIONS = mapOf(
            "00" to Pair(18, "SSCC"),
            "01" to Pair(14, "GTIN"),
            "02" to Pair(14, "GTIN of contained items"),
            "10" to Pair(-1, "Batch/Lot number"),
            "11" to Pair(6, "Production date"),
            "12" to Pair(6, "Due date"),
            "13" to Pair(6, "Packaging date"),
            "15" to Pair(6, "Best before date"),
            "16" to Pair(6, "Sell by date"),
            "17" to Pair(6, "Expiration date"),
            "20" to Pair(2, "Internal product variant"),
            "21" to Pair(-1, "Serial number"),
            "22" to Pair(-1, "Consumer product variant"),
            "30" to Pair(-1, "Variable count"),
            "37" to Pair(-1, "Number of units contained"),
            "240" to Pair(-1, "Additional product ID"),
            "241" to Pair(-1, "Customer part number"),
            "250" to Pair(-1, "Secondary serial number"),
            "251" to Pair(-1, "Reference to source entity"),
            "253" to Pair(-1, "GDTI"),
            "310" to Pair(6, "Net weight (kg)"), // 310x where x is decimal places
            "311" to Pair(6, "Length (m)"),
            "312" to Pair(6, "Width (m)"),
            "313" to Pair(6, "Height (m)"),
            "314" to Pair(6, "Area (m²)"),
            "315" to Pair(6, "Net volume (l)"),
            "316" to Pair(6, "Net volume (m³)"),
            "320" to Pair(6, "Net weight (lb)"),
            "330" to Pair(6, "Gross weight (kg)"),
            "390" to Pair(-1, "Amount payable (local currency)"),
            "391" to Pair(-1, "Amount payable (with ISO currency)"),
            "392" to Pair(-1, "Amount payable per item (local currency)"),
            "393" to Pair(-1, "Amount payable per item (with ISO currency)"),
            "400" to Pair(-1, "Customer's purchase order number"),
            "410" to Pair(13, "Ship to GLN"),
            "411" to Pair(13, "Bill to GLN"),
            "412" to Pair(13, "Purchased from GLN"),
            "413" to Pair(13, "Ship for GLN"),
            "414" to Pair(13, "GLN physical location"),
            "420" to Pair(-1, "Ship to postal code"),
            "421" to Pair(-1, "Ship to postal code with ISO country"),
            "422" to Pair(3, "Country of origin"),
            "423" to Pair(-1, "Country of initial processing"),
            "424" to Pair(3, "Country of processing"),
            "425" to Pair(-1, "Country of disassembly"),
            "426" to Pair(3, "Country covering full process chain"),
            "7003" to Pair(10, "Expiration date and time"),
            "8020" to Pair(-1, "Payment slip reference number")
        )

        // Date format for GS1 dates (YYMMDD)
        private val dateFormat = SimpleDateFormat("yyMMdd", Locale.US)
    }

    /**
     * Check if barcode contains GS1 data
     */
    fun isGS1Barcode(barcode: String): Boolean {
        // GS1 DataMatrix starts with ]d2 or FNC1
        // GS1-128 starts with ]C1 or FNC1
        return barcode.startsWith("]d2") ||
                barcode.startsWith("]C1") ||
                barcode.startsWith("${FNC1}") ||
                barcode.contains(GS) ||
                // Check for common AI patterns at start
                barcode.startsWith("01") && barcode.length >= 16 ||
                barcode.startsWith("(01)")
    }

    /**
     * Parse GS1 barcode and extract all Application Identifiers
     */
    fun parse(barcode: String): GS1Data? {
        if (barcode.isBlank()) return null

        return try {
            val cleanBarcode = cleanBarcode(barcode)
            val ais = extractAIs(cleanBarcode)

            if (ais.isEmpty()) {
                Log.d(TAG, "No AIs found in barcode")
                return null
            }

            Log.d(TAG, "Parsed AIs: $ais")

            GS1Data(
                gtin = ais["01"],
                batchNumber = ais["10"],
                serialNumber = ais["21"],
                expirationDate = parseGS1Date(ais["17"]),
                productionDate = parseGS1Date(ais["11"]),
                quantity = ais["30"]?.toIntOrNull() ?: ais["37"]?.toIntOrNull(),
                weight = parseWeight(ais),
                rawAIs = ais
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GS1 barcode: ${e.message}", e)
            null
        }
    }

    /**
     * Clean barcode by removing symbology identifiers and normalizing
     */
    private fun cleanBarcode(barcode: String): String {
        var clean = barcode

        // Remove symbology identifiers
        if (clean.startsWith("]d2") || clean.startsWith("]C1")) {
            clean = clean.substring(3)
        }

        // Remove FNC1 at start (sometimes encoded as specific characters)
        if (clean.startsWith("${FNC1}")) {
            clean = clean.substring(1)
        }

        // Remove parentheses around AIs (human-readable format)
        clean = clean.replace(Regex("\\(([0-9]+)\\)"), "$1")

        return clean
    }

    /**
     * Extract Application Identifiers and their values
     */
    private fun extractAIs(barcode: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var position = 0

        while (position < barcode.length) {
            // Skip group separators
            if (barcode[position] == GS || barcode[position] == FNC1) {
                position++
                continue
            }

            // Try to match an AI
            val aiMatch = findAI(barcode, position)
            if (aiMatch != null) {
                val (ai, length) = aiMatch
                val valueStart = position + ai.length

                // Get the AI definition
                val aiDef = getAIDefinition(ai)
                val fixedLength = aiDef?.first ?: -1

                val value: String
                val valueEnd: Int

                if (fixedLength > 0) {
                    // Fixed length AI
                    valueEnd = minOf(valueStart + fixedLength, barcode.length)
                    value = barcode.substring(valueStart, valueEnd)
                } else {
                    // Variable length AI - read until GS or end
                    val gsPos = barcode.indexOf(GS, valueStart)
                    val fnc1Pos = barcode.indexOf(FNC1, valueStart)

                    valueEnd = when {
                        gsPos >= 0 && fnc1Pos >= 0 -> minOf(gsPos, fnc1Pos)
                        gsPos >= 0 -> gsPos
                        fnc1Pos >= 0 -> fnc1Pos
                        else -> {
                            // Look for next AI pattern
                            findNextAIStart(barcode, valueStart) ?: barcode.length
                        }
                    }
                    value = barcode.substring(valueStart, valueEnd)
                }

                result[ai] = value
                position = valueEnd
            } else {
                // No AI found, move forward
                position++
            }
        }

        return result
    }

    /**
     * Find AI at current position
     */
    private fun findAI(barcode: String, position: Int): Pair<String, Int>? {
        // Try 4-digit AIs first (e.g., 7003, 8020)
        if (position + 4 <= barcode.length) {
            val ai4 = barcode.substring(position, position + 4)
            if (AI_DEFINITIONS.containsKey(ai4)) {
                return Pair(ai4, 4)
            }
        }

        // Try 3-digit AIs (e.g., 310, 240)
        if (position + 3 <= barcode.length) {
            val ai3 = barcode.substring(position, position + 3)
            // Check for 31xx pattern (weight with decimal indicator)
            if (ai3.matches(Regex("3[0-3][0-9]"))) {
                return Pair(ai3, 3)
            }
            if (AI_DEFINITIONS.containsKey(ai3)) {
                return Pair(ai3, 3)
            }
        }

        // Try 2-digit AIs
        if (position + 2 <= barcode.length) {
            val ai2 = barcode.substring(position, position + 2)
            if (AI_DEFINITIONS.containsKey(ai2)) {
                return Pair(ai2, 2)
            }
        }

        return null
    }

    /**
     * Find start of next AI in barcode
     */
    private fun findNextAIStart(barcode: String, startPos: Int): Int? {
        for (i in startPos until barcode.length - 1) {
            // Check if this position could be start of a known AI
            val remaining = barcode.length - i
            if (remaining >= 2) {
                val potential2 = barcode.substring(i, i + 2)
                if (AI_DEFINITIONS.containsKey(potential2)) {
                    return i
                }
            }
            if (remaining >= 3) {
                val potential3 = barcode.substring(i, i + 3)
                if (AI_DEFINITIONS.containsKey(potential3) || potential3.matches(Regex("3[0-3][0-9]"))) {
                    return i
                }
            }
        }
        return null
    }

    /**
     * Get AI definition
     */
    private fun getAIDefinition(ai: String): Pair<Int, String>? {
        // Direct match
        AI_DEFINITIONS[ai]?.let { return it }

        // Pattern match for 31xx (weight with decimal)
        if (ai.matches(Regex("3[0-3][0-9]"))) {
            return Pair(6, "Measure")
        }

        return null
    }

    /**
     * Parse GS1 date (YYMMDD format)
     */
    private fun parseGS1Date(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank() || dateStr.length != 6) return null

        return try {
            // Handle special case where day is 00 (means last day of month)
            val year = dateStr.substring(0, 2).toInt()
            val month = dateStr.substring(2, 4).toInt()
            var day = dateStr.substring(4, 6).toInt()

            if (day == 0) {
                // Last day of month
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.YEAR, if (year > 50) 1900 + year else 2000 + year)
                calendar.set(Calendar.MONTH, month - 1)
                day = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            }

            val calendar = Calendar.getInstance()
            calendar.set(Calendar.YEAR, if (year > 50) 1900 + year else 2000 + year)
            calendar.set(Calendar.MONTH, month - 1)
            calendar.set(Calendar.DAY_OF_MONTH, day)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            calendar.timeInMillis
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse date: $dateStr", e)
            null
        }
    }

    /**
     * Parse weight from 31xx AIs
     */
    private fun parseWeight(ais: Map<String, String>): Double? {
        // Look for weight AIs (310x - 316x for metric, 320x - 329x for imperial)
        for ((ai, value) in ais) {
            if (ai.matches(Regex("3[0-3][0-9]")) && value.length == 6) {
                val decimalPlaces = ai.last().toString().toInt()
                val rawValue = value.toLongOrNull() ?: continue
                return rawValue / Math.pow(10.0, decimalPlaces.toDouble())
            }
        }
        return null
    }

    /**
     * Extract GTIN from regular EAN/UPC barcode (non-GS1)
     */
    fun extractGTIN(barcode: String): String? {
        val clean = barcode.trim()
        return when (clean.length) {
            8 -> clean   // EAN-8
            12 -> "0$clean" // UPC-A -> GTIN-13
            13 -> clean  // EAN-13
            14 -> clean  // GTIN-14
            else -> null
        }
    }
}
