package ua.com.programmer.pick.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentQrTest {

    private val server = "https://pick.example.net"

    @Test
    fun `reads the token of a code for this server`() {
        val qr = EnrollmentQr.parse("pick://enroll?s=https%3A%2F%2Fpick.example.net&t=abc123", server)
        assertEquals(EnrollmentQr.Valid("abc123"), qr)
    }

    @Test
    fun `server match ignores case, trailing slash and parameter order`() {
        val qr = EnrollmentQr.parse("pick://enroll?t=abc123&s=HTTPS%3A%2F%2FPick.Example.net%2F", "$server/")
        assertEquals(EnrollmentQr.Valid("abc123"), qr)
    }

    @Test
    fun `refuses a code issued by another server`() {
        val qr = EnrollmentQr.parse("pick://enroll?s=https%3A%2F%2Fevil.example.com&t=abc123", server)
        assertEquals(EnrollmentQr.OtherServer("https://evil.example.com"), qr)
    }

    @Test
    fun `refuses a plain-http origin of the same host`() {
        val qr = EnrollmentQr.parse("pick://enroll?s=http%3A%2F%2Fpick.example.net&t=abc123", server)
        assertEquals(EnrollmentQr.OtherServer("http://pick.example.net"), qr)
    }

    @Test
    fun `a code without a server is accepted`() {
        assertEquals(EnrollmentQr.Valid("abc123"), EnrollmentQr.parse("pick://enroll?t=abc123", server))
    }

    @Test
    fun `other barcodes and a missing token are not enrollment codes`() {
        assertNull(EnrollmentQr.parse("4820000000017", server))
        assertNull(EnrollmentQr.parse("USR:bG9naW46cGFzcw==", server))
        assertNull(EnrollmentQr.parse("pick://enroll?s=https%3A%2F%2Fpick.example.net", server))
        assertNull(EnrollmentQr.parse("pick://enroll?t=", server))
    }

    @Test
    fun `surrounding whitespace from a keyboard-wedge scanner is ignored`() {
        assertEquals(EnrollmentQr.Valid("abc123"), EnrollmentQr.parse("  pick://enroll?t=abc123\n", server))
    }
}
