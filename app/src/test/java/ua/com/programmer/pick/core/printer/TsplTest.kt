package ua.com.programmer.pick.core.printer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsplTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `mono packing - black is zero bit, rows padded white`() {
        // 10 px wide → 2 bytes per row; row 0: x=0 and x=9 black, row 1: all white.
        val pixels = IntArray(20) { white }
        pixels[0] = black
        pixels[9] = black
        val img = MonoImage.fromArgb(10, 2, pixels)
        assertEquals(2, img.widthBytes)
        assertArrayEquals(
            byteArrayOf(0x7F, 0xBF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            img.data
        )
    }

    @Test
    fun `mono packing - inverted polarity`() {
        val pixels = IntArray(8) { white }
        pixels[7] = black
        val img = MonoImage.fromArgb(8, 1, pixels, blackIsZero = false)
        assertArrayEquals(byteArrayOf(0x01), img.data)
    }

    @Test
    fun `mono packing - transparent and light pixels are white`() {
        val pixels = intArrayOf(0x00000000, 0xFFC0C0C0.toInt(), 0xFF404040.toInt(), white, white, white, white, white)
        val img = MonoImage.fromArgb(8, 1, pixels)
        assertArrayEquals(byteArrayOf(0xDF.toByte()), img.data)
    }

    @Test
    fun `bitmap command embeds raw bytes between header and CRLF`() {
        val img = MonoImage.fromArgb(8, 1, IntArray(8) { black })
        val out = TsplBuilder().bitmap(0, 0, img).build()
        val header = "BITMAP 0,0,1,1,0,".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(header + byteArrayOf(0x00, '\r'.code.toByte(), '\n'.code.toByte()), out)
    }

    @Test
    fun `setup writes size gap and density`() {
        val text = String(TsplBuilder().setup(LabelSpec(85, 85, gapMm = 3, density = 20)).build(), Charsets.US_ASCII)
        assertTrue(text.startsWith("SIZE 85 mm,85 mm\r\nGAP 3 mm,0 mm\r\n"))
        assertTrue(text.contains("DENSITY 15\r\n"))
        assertTrue(text.endsWith("CLS\r\n"))
    }

    @Test
    fun `quotes in text are escaped`() {
        val text = String(TsplBuilder().text(0, 0, "3", 1, "a\"b").build(), Charsets.US_ASCII)
        assertEquals("TEXT 0,0,\"3\",0,1,1,\"a\\[\"]b\"\r\n", text)
    }

    @Test
    fun `status byte decoding`() {
        assertTrue(PrinterStatus(0).isReady)
        val s = PrinterStatus(0x05)
        assertFalse(s.isReady)
        assertEquals(listOf("HEAD_OPEN", "PAPER_OUT"), s.flags())
    }

    @Test
    fun `ean13 check digit`() {
        assertEquals(7, Ean13.checkDigit("590123412345"))
        assertEquals("4820000000017", Ean13.complete("482000000001"))
    }

    @Test
    fun `ean13 modules have guards and valid digit shapes`() {
        val m = Ean13.modules(TestLabels.SAMPLE_EAN)
        assertEquals(95, m.size)
        fun bits(from: Int, len: Int) = (from until from + len).joinToString("") { if (m[it]) "1" else "0" }
        assertEquals("101", bits(0, 3))
        assertEquals("01010", bits(45, 5))
        assertEquals("101", bits(92, 3))
        // Left digits start with a space, right digits with a bar; each digit is
        // exactly two bars and two spaces.
        for (d in 0 until 6) {
            val left = bits(3 + d * 7, 7)
            val right = bits(50 + d * 7, 7)
            assertTrue(left.startsWith("0") && left.endsWith("1"))
            assertTrue(right.startsWith("1") && right.endsWith("0"))
            assertEquals(2, Regex("1+").findAll(left).count())
            assertEquals(2, Regex("1+").findAll(right).count())
        }
        // First digit 4 → left parity LGLLGG (L = odd count of bar modules).
        val parity = (0 until 6).joinToString("") { d ->
            if (bits(3 + d * 7, 7).count { it == '1' } % 2 == 1) "L" else "G"
        }
        assertEquals("LGLLGG", parity)
    }
}
