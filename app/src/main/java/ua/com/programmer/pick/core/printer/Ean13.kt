package ua.com.programmer.pick.core.printer

/**
 * EAN-13 module encoder, used to draw a barcode into a bitmap label so a
 * printed bitmap can be checked with the terminal's own scanner.
 */
object Ean13 {

    private val L = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011"
    )

    // Parity of the left half, chosen by the first digit (L = odd, G = even).
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"
    )

    /** Check digit for 12 data digits. */
    fun checkDigit(digits12: String): Int {
        require(digits12.length == 12 && digits12.all { it.isDigit() }) { "12 digits expected" }
        val sum = digits12.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10
    }

    /** Full 13-digit code for 12 data digits. */
    fun complete(digits12: String): String = digits12 + checkDigit(digits12)

    /**
     * The 95 modules of a 13-digit code, `true` = bar. Quiet zones are not
     * included.
     */
    fun modules(code13: String): BooleanArray {
        require(code13.length == 13 && code13.all { it.isDigit() }) { "13 digits expected" }
        require(checkDigit(code13.substring(0, 12)) == code13[12] - '0') { "bad check digit" }
        val parity = PARITY[code13[0] - '0']
        val sb = StringBuilder("101")
        for (i in 1..6) {
            val l = L[code13[i] - '0']
            sb.append(if (parity[i - 1] == 'L') l else g(l))
        }
        sb.append("01010")
        for (i in 7..12) sb.append(r(L[code13[i] - '0']))
        sb.append("101")
        return BooleanArray(sb.length) { sb[it] == '1' }
    }

    private fun r(l: String) = l.map { if (it == '0') '1' else '0' }.joinToString("")

    private fun g(l: String) = r(l).reversed()
}
