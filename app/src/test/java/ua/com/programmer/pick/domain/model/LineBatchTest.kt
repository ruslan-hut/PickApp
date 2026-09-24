package ua.com.programmer.pick.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors the server's entity.NormalizeLineBatches cases. */
class LineBatchTest {

    private fun b(id: String, q: Double) = LineBatch(id, q)

    @Test
    fun `normalize drops blanks, merges repeats and trims the latest scans first`() {
        assertEquals(listOf(b("A", 2.0), b("B", 3.0)), listOf(b("A", 2.0), b("B", 3.0)).normalizedTo(8.0))
        assertEquals(listOf(b("C", 1.0)), listOf(b("", 2.0), b("A", 0.0), b("C", 1.0)).normalizedTo(8.0))
        assertEquals(listOf(b("A", 3.0), b("B", 1.0)), listOf(b("A", 1.0), b("B", 1.0), b("A", 2.0)).normalizedTo(8.0))
        assertEquals(listOf(b("A", 3.0), b("B", 2.0)), listOf(b("A", 3.0), b("B", 4.0)).normalizedTo(5.0))
        assertEquals(listOf(b("A", 3.0)), listOf(b("A", 3.0), b("B", 4.0)).normalizedTo(3.0))
        assertEquals(emptyList<LineBatch>(), listOf(b("A", 3.0)).normalizedTo(0.0))
    }

    @Test
    fun `credit adds to an existing batch or appends a new one`() {
        assertEquals(listOf(b("A", 2.0)), listOf(b("A", 1.0)).credit("A", 1.0))
        assertEquals(listOf(b("A", 1.0), b("B", 1.0)), listOf(b("A", 1.0)).credit("B", 1.0))
    }

    @Test
    fun `json keeps null distinct from an empty breakdown`() {
        assertNull(LineBatchJson.encode(null))
        assertNull(LineBatchJson.decode(null))
        assertEquals(emptyList<LineBatch>(), LineBatchJson.decode(LineBatchJson.encode(emptyList())))
        assertEquals(listOf(b("A", 2.0)), LineBatchJson.decode(LineBatchJson.encode(listOf(b("A", 2.0)))))
    }
}

class AvailableDocumentTypeFlowTest {
    @Test
    fun `receiving is named by wms_flow, not by the type code`() {
        org.junit.Assert.assertTrue(AvailableDocumentType("ПриходнаяНакладная", "ПН", wmsFlow = "receive").isGuidedReceiving)
        org.junit.Assert.assertFalse(AvailableDocumentType("INCOMING_RECEIPT", "ПН").isGuidedReceiving)
        org.junit.Assert.assertFalse(AvailableDocumentType("РасходнаяНакладная", "РН", wmsFlow = "collect").isGuidedReceiving)
    }
}
