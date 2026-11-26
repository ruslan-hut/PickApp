package ua.com.programmer.pick.data.sync

import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.remote.transport.ConnectionState
import ua.com.programmer.pick.data.remote.transport.SyncMessage
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState

/**
 * T76b54084: with no stage lock held nothing polls, so a refused
 * "Take into work" left the stale LOADED snapshot on screen and the worker
 * pressed again 26 times. A refusal must ask the server at once; the reply's
 * visible_ids then purges the document.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorLockRefusalTest {

    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    @Test
    fun `already locked asks for a delta sync`() = runTest {
        val (orchestrator, transport) = build(refusal("document is already locked"))

        orchestrator.lockForStage(DOC, "collect")

        verify(exactly = 1) { transport.sendMessage(match { it is SyncMessage.SyncRequest }) }
        assertFalse(orchestrator.hasStageLockClaim(DOC))
    }

    @Test
    fun `assigned to another worker asks for a delta sync`() = runTest {
        val (orchestrator, transport) = build(refusal("document is assigned to another worker"))

        orchestrator.lockForStage(DOC, "collect")

        verify(exactly = 1) { transport.sendMessage(match { it is SyncMessage.SyncRequest }) }
    }

    @Test
    fun `lock-loss recovery attempts do not refresh on each refusal`() = runTest {
        val (orchestrator, transport) = build(refusal("document is already locked"))

        orchestrator.lockForStage(DOC, "collect", refreshOnRefusal = false)

        verify(exactly = 0) { transport.sendMessage(match { it is SyncMessage.SyncRequest }) }
    }

    @Test
    fun `a granted lock does not refresh`() = runTest {
        val granted = SyncMessage.StageLockResult(
            id = "m", timestamp = "t", documentId = DOC, stage = "collect", success = true, lockedBy = "me",
        )
        val (orchestrator, transport) = build(granted)

        orchestrator.lockForStage(DOC, "collect")

        verify(exactly = 0) { transport.sendMessage(match { it is SyncMessage.SyncRequest }) }
    }

    private fun refusal(error: String) = SyncMessage.StageLockResult(
        id = "m", timestamp = "t", documentId = DOC, stage = "collect", success = false, error = error,
    )

    private fun build(answer: SyncMessage.StageLockResult): Pair<SyncOrchestrator, SyncTransport> {
        val transport = mockk<SyncTransport>(relaxed = true) {
            every { userAuthState } returns MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)
            every { connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
            every { incomingMessages } returns MutableSharedFlow()
            every { requiresPolling } returns false
            every { isConnected() } returns true
            every { isUserAuthenticated() } returns true
            every { sendMessage(any()) } returns true
            coEvery { sendAndAwait(any(), SyncMessage.StageLockResult::class.java, any()) } returns answer
        }
        val documentDao = mockk<DocumentDao>(relaxed = true) {
            coEvery { getDocumentById(any()) } returns null
            coEvery { getDirtyDocuments() } returns emptyList()
        }
        val documentLineDao = mockk<DocumentLineDao>(relaxed = true) {
            coEvery { getLinesByDocumentIdSync(any()) } returns emptyList()
        }
        val orchestrator = SyncOrchestrator(
            transport = transport,
            messageParser = mockk(relaxed = true),
            appPreferences = mockk(relaxed = true),
            syncStateDao = mockk(relaxed = true) { coEvery { getCursorsMap() } returns emptyMap() },
            documentDao = documentDao,
            documentLineDao = documentLineDao,
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
        return orchestrator to transport
    }

    private companion object {
        const val DOC = "DOC-140266"
    }
}
