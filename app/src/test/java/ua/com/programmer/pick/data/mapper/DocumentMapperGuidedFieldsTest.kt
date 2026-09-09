package ua.com.programmer.pick.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.com.programmer.pick.data.remote.dto.DocumentDto
import ua.com.programmer.pick.data.remote.dto.DocumentLineDto

/**
 * `collect_mode` and `line_key` must survive DTO → entity → domain → entity so
 * the detail screen can pick the guided bar and guided line updates can address
 * a line by its ERP key. A payload without them (classic tenant, older server)
 * must yield nulls, leaving every existing path untouched.
 */
class DocumentMapperGuidedFieldsTest {

    private val mapper = DocumentMapper()

    @Test
    fun `carries collect_mode through entity and domain`() {
        val entity = mapper.toEntity(documentDto(collectMode = "guided"))
        assertEquals("guided", entity.collectMode)

        val domain = entity.toDomain()
        assertEquals("guided", domain.collectMode)
        assertTrue(domain.isGuidedCollect)

        assertEquals("guided", domain.toEntity().collectMode)
    }

    @Test
    fun `a document without collect_mode is not guided`() {
        val domain = mapper.toEntity(documentDto(collectMode = null)).toDomain()

        assertNull(domain.collectMode)
        assertFalse(domain.isGuidedCollect)
    }

    @Test
    fun `an unknown collect_mode value is not treated as guided`() {
        val domain = mapper.toEntity(documentDto(collectMode = "GUIDED")).toDomain()

        assertFalse(domain.isGuidedCollect)
    }

    @Test
    fun `carries line_key through entity and domain`() {
        val entity = mapper.toLineEntity(lineDto(lineKey = "K1"))
        assertEquals("K1", entity.lineKey)

        val domain = entity.toDomain()
        assertEquals("K1", domain.lineKey)
        assertEquals("K1", domain.toEntity().lineKey)
    }

    @Test
    fun `a line without line_key maps to null`() {
        assertNull(mapper.toLineEntity(lineDto(lineKey = null)).toDomain().lineKey)
    }

    private fun documentDto(collectMode: String?) = DocumentDto(
        id = "doc-1",
        externalId = "ERP-DOC-001",
        type = "OUTGOING_SHIPMENT",
        number = "001",
        date = 0,
        state = "LOADED",
        clientId = null,
        clientName = null,
        warehouseId = null,
        warehouseName = null,
        collectMode = collectMode,
        notes = null,
        totalPlanned = 0.0,
        totalActual = 0.0,
        assignedUserId = null,
        takenAt = null,
        completedAt = null,
        lastModified = 0,
        version = 1,
    )

    private fun lineDto(lineKey: String?) = DocumentLineDto(
        id = "line-1",
        documentId = "doc-1",
        lineNumber = 1,
        lineKey = lineKey,
        productId = "p-1",
        plannedQuantity = 10.0,
        actualQuantity = 0.0,
        batchNumber = null,
        expirationDate = null,
        locationId = null,
        locationPath = null,
        notes = null,
    )
}
