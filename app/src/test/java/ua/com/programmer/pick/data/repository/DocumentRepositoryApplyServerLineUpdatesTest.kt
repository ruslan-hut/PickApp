package ua.com.programmer.pick.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.local.database.entity.DocumentEntity
import ua.com.programmer.pick.domain.model.TaskLineUpdate

/**
 * D5: a guided task's line updates go straight into Room — matched by
 * `line_key` when there is one, else by `line_number`, and never via the
 * dirty-arming setters, because these are the server's own values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentRepositoryApplyServerLineUpdatesTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var documentDao: DocumentDao
    private lateinit var documentLineDao: DocumentLineDao
    private lateinit var repository: DocumentRepositoryImpl

    @Before
    fun setUp() {
        documentDao = mockk(relaxed = true)
        documentLineDao = mockk(relaxed = true)
        repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            documentLineDao = documentLineDao,
            documentLineBarcodeDao = mockk(relaxed = true),
            ioDispatcher = dispatcher,
        )
        coEvery { documentDao.getDocumentByExternalId("ERP-DOC-1") } returns document()
    }

    @Test
    fun `matches by line key when the update carries one`() = runTest {
        coEvery {
            documentLineDao.updateActualByLineKey("doc-1", "K1", 8.0, true)
        } returns 1

        val applied = repository.applyServerLineUpdates(
            "ERP-DOC-1",
            listOf(TaskLineUpdate(lineKey = "K1", lineNumber = 1, actualQuantity = 8.0, isCompleted = true)),
        )

        assertEquals(1, applied)
        coVerify(exactly = 0) { documentLineDao.updateActualByLineNumber(any(), any(), any(), any()) }
    }

    @Test
    fun `falls back to line number when the key matches nothing`() = runTest {
        coEvery { documentLineDao.updateActualByLineKey(any(), any(), any(), any()) } returns 0
        coEvery { documentLineDao.updateActualByLineNumber("doc-1", 3, 5.0, false) } returns 1

        val applied = repository.applyServerLineUpdates(
            "ERP-DOC-1",
            listOf(TaskLineUpdate(lineKey = "GONE", lineNumber = 3, actualQuantity = 5.0, isCompleted = false)),
        )

        assertEquals(1, applied)
        coVerify(exactly = 1) { documentLineDao.updateActualByLineNumber("doc-1", 3, 5.0, false) }
    }

    @Test
    fun `an update without a key goes straight to line number`() = runTest {
        coEvery { documentLineDao.updateActualByLineNumber("doc-1", 2, 4.0, true) } returns 1

        val applied = repository.applyServerLineUpdates(
            "ERP-DOC-1",
            listOf(TaskLineUpdate(lineNumber = 2, actualQuantity = 4.0, isCompleted = true)),
        )

        assertEquals(1, applied)
        coVerify(exactly = 0) { documentLineDao.updateActualByLineKey(any(), any(), any(), any()) }
    }

    @Test
    fun `recomputes the document total without arming the dirty flag`() = runTest {
        coEvery { documentLineDao.updateActualByLineNumber(any(), any(), any(), any()) } returns 1

        repository.applyServerLineUpdates(
            "ERP-DOC-1",
            listOf(TaskLineUpdate(lineNumber = 1, actualQuantity = 8.0, isCompleted = true)),
        )

        coVerify(exactly = 1) { documentDao.recomputeTotalActualForDocs(listOf("doc-1")) }
        // updateTotalActual and markDocumentDirty both set is_dirty = 1, which
        // would make resyncDirtyDocuments push the server's own numbers back.
        coVerify(exactly = 0) { documentDao.updateTotalActual(any(), any(), any()) }
        coVerify(exactly = 0) { documentDao.markDocumentDirty(any(), any()) }
    }

    @Test
    fun `an unknown document writes nothing`() = runTest {
        coEvery { documentDao.getDocumentByExternalId("MISSING") } returns null
        coEvery { documentDao.getDocumentById("MISSING") } returns null

        val applied = repository.applyServerLineUpdates(
            "MISSING",
            listOf(TaskLineUpdate(lineNumber = 1, actualQuantity = 8.0, isCompleted = true)),
        )

        assertEquals(0, applied)
        coVerify(exactly = 0) { documentDao.recomputeTotalActualForDocs(any()) }
    }

    @Test
    fun `an empty update list is a no-op`() = runTest {
        assertEquals(0, repository.applyServerLineUpdates("ERP-DOC-1", emptyList()))

        coVerify(exactly = 0) { documentDao.getDocumentByExternalId(any()) }
    }

    private fun document() = DocumentEntity(
        id = "doc-1",
        externalId = "ERP-DOC-1",
        type = "OUTGOING_SHIPMENT",
        number = "001",
        date = 0,
        state = "COLLECTING",
        clientId = null,
        clientName = null,
        warehouseId = null,
        warehouseName = null,
        notes = null,
        totalPlanned = 10.0,
        totalActual = 0.0,
        assignedUserId = null,
        takenAt = null,
        completedAt = null,
        lastModified = 0,
        version = 1,
    )
}
