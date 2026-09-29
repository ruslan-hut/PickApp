package ua.com.programmer.pick.core.printer

import java.io.ByteArrayOutputStream

/**
 * Label geometry and print settings for a TSPL label printer (Xprinter XP-4xx,
 * TSC and compatibles). Sizes are in millimetres; [dotsPerMm] is 8 for a 203 dpi head.
 */
data class LabelSpec(
    val widthMm: Int,
    val heightMm: Int,
    val gapMm: Int = 2,
    val density: Int = 8,
    val speed: Int = 4,
    val dotsPerMm: Int = 8
) {
    val widthDots: Int get() = widthMm * dotsPerMm
    val heightDots: Int get() = heightMm * dotsPerMm
}

/**
 * Minimal TSPL command builder. Every command ends with CRLF; [bitmap] embeds
 * raw bytes, which is why the output is a byte array rather than a string.
 * TSPL is ASCII-only — built-in fonts print no Cyrillic; anything localized
 * goes through [bitmap].
 */
class TsplBuilder {

    private val out = ByteArrayOutputStream()

    fun setup(spec: LabelSpec) = apply {
        command("SIZE ${spec.widthMm} mm,${spec.heightMm} mm")
        command("GAP ${spec.gapMm} mm,0 mm")
        command("DIRECTION 1,0")
        command("REFERENCE 0,0")
        command("DENSITY ${spec.density.coerceIn(0, 15)}")
        command("SPEED ${spec.speed}")
        command("CLS")
    }

    fun box(x: Int, y: Int, xEnd: Int, yEnd: Int, thickness: Int) =
        command("BOX $x,$y,$xEnd,$yEnd,$thickness")

    fun text(x: Int, y: Int, font: String, scale: Int, content: String) =
        command("TEXT $x,$y,\"$font\",0,$scale,$scale,\"${escape(content)}\"")

    /** [type] is a TSPL symbology name: "128", "EAN13", "39"… */
    fun barcode(x: Int, y: Int, type: String, height: Int, narrow: Int, wide: Int, content: String) =
        command("BARCODE $x,$y,\"$type\",$height,1,0,$narrow,$wide,\"${escape(content)}\"")

    fun qrcode(x: Int, y: Int, cellWidth: Int, content: String) =
        command("QRCODE $x,$y,M,$cellWidth,A,0,\"${escape(content)}\"")

    /** Raw 1-bit image, mode 0 (overwrite). See [MonoImage] for the data layout. */
    fun bitmap(x: Int, y: Int, image: MonoImage) = apply {
        out.write("BITMAP $x,$y,${image.widthBytes},${image.height},0,".toByteArray(Charsets.US_ASCII))
        out.write(image.data)
        out.write(CRLF)
    }

    fun print(copies: Int = 1) = command("PRINT 1,${copies.coerceAtLeast(1)}")

    fun command(line: String) = apply {
        out.write(line.toByteArray(Charsets.US_ASCII))
        out.write(CRLF)
    }

    fun build(): ByteArray = out.toByteArray()

    private fun escape(s: String) = s.replace("\"", "\\[\"]")

    companion object {
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        /** Prints the printer's own configuration page (IP, firmware, emulation). */
        fun selfTest(): ByteArray = TsplBuilder().command("SELFTEST").build()

        /** Auto-detects the label length and gap; feeds a few labels. */
        fun gapDetect(): ByteArray = TsplBuilder().command("GAPDETECT").build()

        /** `<ESC>!?` — immediate status query, answered with a single byte. */
        val STATUS_QUERY = byteArrayOf(0x1B, 0x21, 0x3F)
    }
}

/**
 * A packed 1-bit image in TSPL BITMAP layout: rows top to bottom, 8 pixels per
 * byte, most significant bit first, rows padded to whole bytes.
 *
 * TSPL treats a **0 bit as a printed (black) dot** and 1 as white; the padding
 * is white. [blackIsZero] = false flips that for firmwares that disagree.
 */
class MonoImage private constructor(
    val width: Int,
    val height: Int,
    val widthBytes: Int,
    val data: ByteArray
) {
    companion object {
        /**
         * Packs ARGB pixels (as from `Bitmap.getPixels`) by a luminance
         * threshold. Transparent pixels count as white.
         */
        fun fromArgb(
            width: Int,
            height: Int,
            pixels: IntArray,
            threshold: Int = 128,
            blackIsZero: Boolean = true
        ): MonoImage {
            require(pixels.size >= width * height) { "pixel buffer too small" }
            val widthBytes = (width + 7) / 8
            val data = ByteArray(widthBytes * height)
            val whiteByte: Byte = if (blackIsZero) 0xFF.toByte() else 0
            data.fill(whiteByte)
            for (y in 0 until height) {
                val row = y * widthBytes
                for (x in 0 until width) {
                    if (!isBlack(pixels[y * width + x], threshold)) continue
                    val index = row + (x shr 3)
                    val mask = 0x80 ushr (x and 7)
                    val b = data[index].toInt()
                    data[index] = (if (blackIsZero) b and mask.inv() else b or mask).toByte()
                }
            }
            return MonoImage(width, height, widthBytes, data)
        }

        private fun isBlack(argb: Int, threshold: Int): Boolean {
            val a = argb ushr 24 and 0xFF
            if (a < 128) return false
            val r = argb ushr 16 and 0xFF
            val g = argb ushr 8 and 0xFF
            val b = argb and 0xFF
            // ITU-R BT.601 luma, integer form.
            return (r * 299 + g * 587 + b * 114) / 1000 < threshold
        }
    }
}

/** Decoded answer to [TsplBuilder.STATUS_QUERY]. 0x00 = ready. */
data class PrinterStatus(val raw: Int) {
    val isReady: Boolean get() = raw == 0
    val headOpen: Boolean get() = raw and 0x01 != 0
    val paperJam: Boolean get() = raw and 0x02 != 0
    val paperOut: Boolean get() = raw and 0x04 != 0
    val ribbonOut: Boolean get() = raw and 0x08 != 0
    val paused: Boolean get() = raw and 0x10 != 0
    val printing: Boolean get() = raw and 0x20 != 0
    val otherError: Boolean get() = raw and 0x80 != 0

    fun flags(): List<String> = buildList {
        if (headOpen) add("HEAD_OPEN")
        if (paperJam) add("PAPER_JAM")
        if (paperOut) add("PAPER_OUT")
        if (ribbonOut) add("RIBBON_OUT")
        if (paused) add("PAUSED")
        if (printing) add("PRINTING")
        if (otherError) add("OTHER_ERROR")
    }

    override fun toString(): String =
        "0x%02X %s".format(raw, if (isReady) "READY" else flags().joinToString(","))
}
