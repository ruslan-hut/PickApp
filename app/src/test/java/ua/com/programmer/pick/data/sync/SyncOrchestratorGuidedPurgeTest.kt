package ua.com.programmer.pick.data.sync

import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.entity.DocumentEntity
import ua.com.programmer.pick.data.remote.transport.ConnectionState
import ua.com.programmer.pick.data.remote.transport.SyncMessage
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.TaskState
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository

/**
 * A receiving document another worker holds is absent from the list the server
 * returns to *this* worker, yet they joined it through the server's picker and
 * are standing in front of it. The complete-set purge must keep it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorGuidedPurgeTest {

    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    @Test
    fun `a full-set refresh keeps the active task's document`() = runTest {
        val documentDao = mockk<DocumentDao>(relaxed = true)
        coEvery { documentDao.getDocumentByExternalId("ERP-JOINED") } returns entity()
        val incoming = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 4)

        buildOrchestrator(documentDao, incoming, activeDocumentId = "ERP-JOINED").initialize()
        incoming.emit(emptyDocumentListRefresh())

        // Not deleteAllDocuments: the joined document must survive an otherwise
        // empty authoritative set.
        coVerify(exactly = 0) { documentDao.deleteAllDocuments() }
        coVerify(exactly = 1) {
            documentDao.deleteDocumentsNotIn(match { it.containsAll(listOf("ERP-JOINED", "doc-joined")) })
        }
    }

    @Test
    fun `without an active task an empty full set still purges everything`() = runTest {
        val documentDao = mockk<DocumentDao>(relaxed = true)
        val incoming = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 4)

        buildOrchestrator(documentDao, incoming, activeDocumentId = null).initialize()
        incoming.emit(emptyDocumentListRefresh())

        coVerify(exactly = 1) { documentDao.deleteAllDocuments() }
    }

    private fun emptyDocumentListRefresh() = SyncMessage.SyncData(
        id = "m1",
        timestamp = "2026-09-09T00:00:00Z",
        entityType = "documents",
        data = Gson().toJsonTree(emptyList<String>()),
        fullSet = true,
    )

    private fun entity() = DocumentEntity(
        id = "doc-joined",
        externalId = "ERP-JOINED",
        type = "INCOMING_RECEIPT",
        number = "001",
        date = 0,
        state = "COLLECTING",
        clientId = null,
        clientName = null,
        warehouseId = null,
        warehouseName = null,
        notes = null,
        totalPlanned = 0.0,
        totalActual = 0.0,
        assignedUserId = null,
        takenAt = null,
        completedAt = null,
        lastModified = 0,
        version = 1,
    )

    private fun buildOrchestrator(
        documentDao: DocumentDao,
        incoming: MutableSharedFlow<SyncMessage>,
        activeDocumentId: String?,
    ): SyncOrchestrator {
        val transport = mockk<SyncTransport>(relaxed = true) {
            every { userAuthState } returns MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)
            every { connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
            every { incomingMessages } returns incoming
            every { requiresPolling } returns false
        }
        val active = MutableStateFlow(
            activeDocumentId?.let {
                GuidedTask(id = "t1", type = "INCOMING_RECEIPT", documentId = it, state = TaskState.OPEN)
            },
        )
        return SyncOrchestrator(
            transport = transport,
            messageParser = mockk(relaxed = true),
            appPreferences = mockk(relaxed = true),
            syncStateDao = mockk(relaxed = true),
            documentDao = documentDao,
            documentLineDao = mockk(relaxed = true),
            documentLineBarcodeDao = mockk(relaxed = true),
            productDao = mockk(relaxed = true),
            productImageDao = mockk(relaxed = true),
            clientDao = mockk(relaxed = true),
            warehouseDao = mockk(relaxed = true),
            userDao = mockk(relaxed = true),
            boxDao = mockk(relaxed = true),
            documentBoxDao = mockk(relaxed = true),
            outgoingOperationRepository = mockk(relaxed = true),
            guidedTaskRepository = mockk<GuidedTaskRepository>(relaxed = true) {
                every { this@mockk.active } returns active
            },
            debugJournal = mockk(relaxed = true),
            debugJournalUploader = mockk(relaxed = true),
            linePhotoUploader = mockk(relaxed = true),
            linePhotoStore = mockk(relaxed = true),
            networkMonitor = mockk(relaxed = true),
            documentMapper = mockk(relaxed = true),
            productMapper = mockk(relaxed = true),
            clientMapper = mockk(relaxed = true),
            warehouseMapper = mockk(relaxed = true),
            gson = Gson(),
            ioDispatcher = dispatcher,
        )
    }
}
