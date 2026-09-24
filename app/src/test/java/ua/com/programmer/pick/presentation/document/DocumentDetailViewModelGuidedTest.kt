package ua.com.programmer.pick.presentation.document

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.R
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.data.repository.DocumentTypeConfigProvider
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskState
import ua.com.programmer.pick.domain.model.TaskStep
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository
import ua.com.programmer.pick.domain.repository.TaskCallResult

/**
 * A document worked as a guided task inside the document screen: the start
 * bar names the type's flow (receiving vs picking) by `wms_flow`, and a task
 * that finishes returns the worker to the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailViewModelGuidedTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val receipt = Document(
        id = "RCV-1", externalId = "RCV-1", type = "ПриходнаяНакладная", number = "А-1", date = 0,
        state = DocumentState.COLLECTING, clientId = null, clientName = null, warehouseId = null,
        warehouseName = null, notes = null, totalPlanned = 10.0, totalActual = 0.0, assignedUserId = null,
        takenAt = null, completedAt = null, lastModified = 0, version = 1, collectMode = "guided",
    )

    private fun build(tasks: GuidedTaskRepository): DocumentDetailViewModel {
        val documents = mockk<DocumentRepository>(relaxed = true) {
            coEvery { getDocumentById("RCV-1") } returns receipt
            every { getLinesByDocumentId("RCV-1") } returns flowOf(emptyList())
        }
        return DocumentDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            documentRepository = documents,
            productImageDao = mockk(relaxed = true),
            barcodeService = mockk<BarcodeService>(relaxed = true) { every { scannedBarcodes } returns MutableSharedFlow() },
            productRepository = mockk(relaxed = true),
            syncOrchestrator = mockk<SyncOrchestrator>(relaxed = true) { every { docSyncEvents } returns MutableSharedFlow() },
            debugJournal = mockk(relaxed = true),
            boxDao = mockk(relaxed = true),
            documentBoxDao = mockk(relaxed = true),
            documentTypeConfigProvider = mockk<DocumentTypeConfigProvider>(relaxed = true) {
                every { configs } returns flowOf(
                    mapOf("ПриходнаяНакладная" to AvailableDocumentType("ПриходнаяНакладная", "ПН", wmsFlow = "receive")),
                )
            },
            imageCompressor = mockk(relaxed = true),
            linePhotoStore = mockk(relaxed = true),
            appPreferences = mockk(relaxed = true) { every { scanOnly } returns flowOf(false) },
            ioDispatcher = mainDispatcher,
            guidedTaskRepository = tasks,
            networkMonitor = mockk(relaxed = true) { every { isOnline } returns flowOf(true) },
        )
    }

    private fun tasks(): GuidedTaskRepository = mockk(relaxed = true) {
        every { openTasks } returns MutableStateFlow(emptyList())
    }

    @Test
    fun `a receiving-flow document says receiving on its start bar, whatever its code`() = runTest {
        val vm = build(tasks())
        vm.load("RCV-1")

        assertTrue(vm.uiState.value.isReceivingType)
        assertEquals(R.string.task_continue_receiving, vm.uiState.value.guidedButtonLabelRes)
    }

    @Test
    fun `a finished task returns to the list`() = runTest {
        val repo = tasks()
        val step = TaskStep(id = "rv_done", title = "Done", actions = listOf(TaskActionButton("done", "Finish", "primary")))
        coEvery { repo.start(null, "RCV-1") } returns TaskCallResult.Success(
            GuidedTask(id = "t1", type = "ПриходнаяНакладная", documentId = "RCV-1", state = TaskState.DONE, stepId = "rv_done", step = step),
        )
        val vm = build(repo)
        vm.load("RCV-1")
        val events = mutableListOf<DocumentDetailUiEvent>()
        backgroundScope.launch(mainDispatcher) { vm.uiEvents.collect { events += it } }

        vm.startGuidedTask()
        vm.guidedAction("done")

        assertTrue("expected NavigateBack, got $events", events.contains(DocumentDetailUiEvent.NavigateBack))
        assertNull(vm.guidedState.value)
    }
}
