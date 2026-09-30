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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.remote.transport.ConnectionState
import ua.com.programmer.pick.data.remote.transport.SyncMessage
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState

/**
 * The answer to our own unlock / pause is a `StageLockResult(success = true)` on
 * the wire. Read as a lock result it re-claimed the lock locally: the sync guard
 * then dropped the server's post-release payload (with `can_park`) and the next
 * queued write triggered a silent re-lock that took the document back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorStageReleaseResultTest {

    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    private fun lockResult(release: Boolean, state: String? = null, version: Long? = null, canPark: Boolean? = null) =
        SyncMessage.StageLockResult(
            id = "m", timestamp = "t", documentId = DOC, stage = "pack", success = true,
            release = release, state = state, version = version, canPark = canPark,
        )

    @Test
    fun `pause result releases the claim and applies the snapshot`() = runTest {
        val documentDao = mockk<DocumentDao>(relaxed = true)
        coEvery { documentDao.getDocumentByExternalId(any()) } returns null
        val incoming = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 8)
        val orchestrator = buildOrchestrator(documentDao, incoming)
        orchestrator.initialize()

        incoming.emit(lockResult(release = false)) // a real lock takes the claim
        assertTrue(orchestrator.hasStageLockClaim(DOC))

        incoming.emit(lockResult(release = true, state = "PACKING", version = 709, canPark = true))

        assertFalse(orchestrator.hasStageLockClaim(DOC))
        coVerify(exactly = 1) { documentDao.applyPaused(DOC, "PACKING", 709, true, any()) }
        // Only the earlier real lock moved the state to in-process.
        coVerify(exactly = 1) { documentDao.updateDocumentStateFromServer(DOC, any(), any()) }
    }

    @Test
    fun `unlock result never re-claims the lock`() = runTest {
        val documentDao = mockk<DocumentDao>(relaxed = true)
        coEvery { documentDao.getDocumentByExternalId(any()) } returns null
        val incoming = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 8)
        val orchestrator = buildOrchestrator(documentDao, incoming)
        orchestrator.initialize()

        incoming.emit(lockResult(release = true))

        assertFalse(orchestrator.hasStageLockClaim(DOC))
        coVerify(exactly = 0) { documentDao.updateDocumentStateFromServer(any(), any(), any()) }
        coVerify(exactly = 0) { documentDao.applyPaused(any(), any(), any(), any(), any()) }
    }

    private fun buildOrchestrator(
        documentDao: DocumentDao,
        incoming: MutableSharedFlow<SyncMessage>,
    ): SyncOrchestrator {
        val transport = mockk<SyncTransport>(relaxed = true) {
            every { userAuthState } returns MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)
            every { connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
            every { incomingMessages } returns incoming
            every { requiresPolling } returns false
        }
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
            guidedTaskRepository = mockk(relaxed = true) {
                every { active } returns MutableStateFlow(null)
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

    private companion object {
        const val DOC = "ERP-DOC-1"
    }
}
