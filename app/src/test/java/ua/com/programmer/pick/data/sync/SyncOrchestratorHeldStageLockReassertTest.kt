package ua.com.programmer.pick.data.sync

import com.google.gson.Gson
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
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.data.remote.transport.ConnectionState
import ua.com.programmer.pick.data.remote.transport.SyncMessage
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState

/**
 * D3 of the guided-tasks plan: a document the worker holds an open guided task
 * on must NOT enter `heldStageLocks`. The lock belongs to the task engine — the
 * device has no dirty edits to protect for it, and treating it as held would
 * arm the classic cooperative force-release path on a task-locked document.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorHeldStageLockReassertTest {

    // Own scheduler: the orchestrator's internal scope must not run on the
    // runTest scheduler, whose uncompleted-coroutine check would trip on the
    // long-lived flow collectors initialize() starts.
    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    @Test
    fun `skips documents carrying an open guided task`() = runTest {
        val documentDao = mockk<DocumentDao>(relaxed = true)
        val authState = MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)

        buildOrchestrator(documentDao, authState).initialize()

        authState.value = UserAuthState.Authenticated(
            userId = "u1",
            userName = "Worker",
            role = "COLLECTOR",
            offlineHash = null,
            heldStageLocks = listOf("ERP-CLASSIC", "ERP-GUIDED"),
            openTasks = listOf(
                DeviceDto.OpenTask(id = "t1", type = "OUTGOING_SHIPMENT", documentId = "ERP-GUIDED"),
            ),
        )

        coVerify(exactly = 1) { documentDao.getDocumentByExternalId("ERP-CLASSIC") }
        coVerify(exactly = 0) { documentDao.getDocumentByExternalId("ERP-GUIDED") }
    }

    @Test
    fun `reasserts every held lock when there are no open tasks`() = runTest {
        val documentDao = mockk<DocumentDao>(relaxed = true)
        val authState = MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)

        buildOrchestrator(documentDao, authState).initialize()

        authState.value = UserAuthState.Authenticated(
            userId = "u1",
            userName = "Worker",
            role = "COLLECTOR",
            offlineHash = null,
            heldStageLocks = listOf("ERP-CLASSIC", "ERP-OTHER"),
            openTasks = null,
        )

        coVerify(exactly = 1) { documentDao.getDocumentByExternalId("ERP-CLASSIC") }
        coVerify(exactly = 1) { documentDao.getDocumentByExternalId("ERP-OTHER") }
    }

    private fun buildOrchestrator(
        documentDao: DocumentDao,
        authState: MutableStateFlow<UserAuthState>,
    ): SyncOrchestrator {
        val transport = mockk<SyncTransport>(relaxed = true) {
            every { userAuthState } returns authState
            every { connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
            every { incomingMessages } returns MutableSharedFlow<SyncMessage>()
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
}
