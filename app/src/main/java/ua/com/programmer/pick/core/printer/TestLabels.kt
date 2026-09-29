package ua.com.programmer.pick.core.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test labels for the printer diagnostics screen. Two jobs on purpose:
 * [textLabel] uses only the printer's own fonts and barcode engine (proves the
 * link and the TSPL dialect), [bitmapLabel] renders everything on the device
 * and sends one BITMAP (the path a carrier PDF label will take).
 */
object TestLabels {

    /** Sample EAN-13 printed on both labels, so the two can be compared by scan. */
    const val SAMPLE_EAN = "4820000000017"

    fun textLabel(spec: LabelSpec, copies: Int = 1): ByteArray {
        val w = spec.widthDots
        val h = spec.heightDots
        val m = 16
        return TsplBuilder()
            .setup(spec)
            .box(m, m, w - m, h - m, 4)
            .text(m + 16, m + 16, "3", 1, "PICK PRINT TEST")
            .text(m + 16, m + 56, "2", 1, "${spec.widthMm}x${spec.heightMm} mm, gap ${spec.gapMm}, density ${spec.density}")
            .text(m + 16, m + 84, "2", 1, timestamp())
            .barcode(m + 16, m + 124, "128", 80, 2, 2, "PICK-TEST-0001")
            .barcode(m + 16, m + 244, "EAN13", 80, 2, 4, SAMPLE_EAN.substring(0, 12))
            .qrcode(w - m - 160, m + 124, 5, "PICK TEST ${timestamp()}")
            .print(copies)
            .build()
    }

    fun bitmapLabel(spec: LabelSpec, blackIsZero: Boolean, copies: Int = 1): ByteArray {
        val image = renderBitmap(spec)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        image.recycle()
        val mono = MonoImage.fromArgb(spec.widthDots, spec.heightDots, pixels, blackIsZero = blackIsZero)
        return TsplBuilder()
            .setup(spec)
            .bitmap(0, 0, mono)
            .print(copies)
            .build()
    }

    /** The bitmap label as drawn, before thresholding. */
    fun renderBitmap(spec: LabelSpec): Bitmap {
        val w = spec.widthDots
        val h = spec.heightDots
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val fill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false }
        val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = false }
        val title = Paint().apply {
            color = Color.BLACK; isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = h * 0.07f
        }
        val body = Paint().apply {
            color = Color.BLACK; isAntiAlias = true
            typeface = Typeface.SANS_SERIF
            textSize = h * 0.045f
        }

        val m = 12f
        canvas.drawRect(m, m, w - m, h - m, stroke)

        var y = m + 12 + title.textSize
        canvas.drawText("PICK · Тест друку", m + 16, y, title)
        y += body.textSize * 1.4f
        canvas.drawText("Бітмап ${w}×${h} точок, ${spec.widthMm}×${spec.heightMm} мм", m + 16, y, body)
        y += body.textSize * 1.3f
        canvas.drawText("${Build.MANUFACTURER} ${Build.MODEL}", m + 16, y, body)
        y += body.textSize * 1.3f
        canvas.drawText(timestamp(), m + 16, y, body)

        // Line-width ladder: 1..4 dot bars, vertical then horizontal. Thin bars
        // that vanish or merge show the head and threshold limits.
        y += 20
        val ladderTop = y
        val ladderHeight = h * 0.12f
        var x = m + 16
        for (width in 1..4) {
            repeat(4) {
                canvas.drawRect(x, ladderTop, x + width, ladderTop + ladderHeight, fill)
                x += width * 2
            }
            x += 16
        }
        var hy = ladderTop
        val hx = x + 8
        val hLen = (w - m - 16) - hx
        if (hLen > 40) {
            for (width in 1..4) {
                repeat(2) {
                    canvas.drawRect(hx, hy, hx + hLen, hy + width, fill)
                    hy += width * 2
                }
                hy += 6
            }
        }
        y = ladderTop + ladderHeight + 16

        // EAN-13 at 3 dots per module (~0.375 mm), drawn as whole dots.
        val module = 3
        val bars = Ean13.modules(SAMPLE_EAN)
        val barHeight = (h - m - 16 - body.textSize * 1.5f - y).coerceIn(40f, h * 0.3f)
        val startX = m + 16 + module * 11
        bars.forEachIndexed { i, bar ->
            if (bar) {
                val bx = startX + i * module
                canvas.drawRect(bx, y, bx + module, y + barHeight, fill)
            }
        }
        canvas.drawText(SAMPLE_EAN, startX, y + barHeight + body.textSize * 1.1f, body)

        return bitmap
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Device identity for the log header. */
    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
}
