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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.data.sync.StageLockResult
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.util.Result

/**
 * T76b54084: after the server refuses "Take into work" the button must not be
 * there to press again; the screen follows whatever the refresh brings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailViewModelLockRefusalTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val loaded = Document(
        id = DOC, externalId = DOC, type = "РасходнаяНакладная", number = "140266", date = 0,
        state = DocumentState.LOADED, clientId = null, clientName = null, warehouseId = null,
        warehouseName = null, notes = null, totalPlanned = 90.0, totalActual = 0.0, assignedUserId = null,
        takenAt = null, completedAt = null, lastModified = 0, version = 1,
    )

    private val cached = MutableStateFlow<Document?>(loaded)

    private fun build(answer: StageLockResult): Pair<DocumentDetailViewModel, SyncOrchestrator> {
        val documents = mockk<DocumentRepository>(relaxed = true) {
            coEvery { getDocumentById(DOC) } answers { cached.value }
            every { getLinesByDocumentId(DOC) } returns flowOf(emptyList())
            every { observeDocument(DOC) } returns cached
        }
        val orchestrator = mockk<SyncOrchestrator>(relaxed = true) {
            every { docSyncEvents } returns MutableSharedFlow()
            coEvery { lockForStage(DOC, any(), any()) } returns Result.Success(answer)
        }
        val tasks = mockk<GuidedTaskRepository>(relaxed = true) {
            every { openTasks } returns MutableStateFlow(emptyList())
        }
        val vm = DocumentDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            documentRepository = documents,
            productImageDao = mockk(relaxed = true),
            barcodeService = mockk<BarcodeService>(relaxed = true) { every { scannedBarcodes } returns MutableSharedFlow() },
            productRepository = mockk(relaxed = true),
            syncOrchestrator = orchestrator,
            debugJournal = mockk(relaxed = true),
            boxDao = mockk(relaxed = true),
            documentBoxDao = mockk(relaxed = true),
            documentTypeConfigProvider = mockk(relaxed = true) { every { configs } returns flowOf(emptyMap()) },
            imageCompressor = mockk(relaxed = true),
            linePhotoStore = mockk(relaxed = true),
            appPreferences = mockk(relaxed = true) { every { scanOnly } returns flowOf(false) },
            ioDispatcher = mainDispatcher,
            guidedTaskRepository = tasks,
            networkMonitor = mockk(relaxed = true) { every { isOnline } returns flowOf(true) },
            parkingRepository = mockk(relaxed = true),
            labelPrintRepository = mockk(relaxed = true),
        )
        return vm to orchestrator
    }

    private fun refused(error: String) =
        StageLockResult(success = false, documentId = DOC, stage = "collect", lockedBy = null, error = error)

    @Test
    fun `a refusal hides Take into work and says the document is taken`() = runTest {
        val (vm, _) = build(refused("document is already locked"))
        vm.load(DOC)
        val events = mutableListOf<DocumentDetailUiEvent>()
        backgroundScope.launch(mainDispatcher) { vm.uiEvents.collect { events += it } }
        assertTrue(vm.uiState.value.canTakeIntoWork)

        vm.takeIntoWork()

        assertTrue(vm.uiState.value.lockRefused)
        assertFalse(vm.uiState.value.canTakeIntoWork)
        assertEquals(listOf(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_BY_OTHER)), events)
    }

    @Test
    fun `assigned to another worker reads as taken by another`() = runTest {
        val (vm, _) = build(refused("document is assigned to another worker"))
        vm.load(DOC)
        val events = mutableListOf<DocumentDetailUiEvent>()
        backgroundScope.launch(mainDispatcher) { vm.uiEvents.collect { events += it } }

        vm.takeIntoWork()

        assertEquals(listOf(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_BY_OTHER)), events)
    }

    @Test
    fun `the refresh purging the document closes the screen`() = runTest {
        val (vm, _) = build(refused("document is already locked"))
        vm.load(DOC)
        val events = mutableListOf<DocumentDetailUiEvent>()
        backgroundScope.launch(mainDispatcher) { vm.uiEvents.collect { events += it } }
        vm.takeIntoWork()

        cached.value = null

        assertTrue("expected NavigateBack, got $events", events.contains(DocumentDetailUiEvent.NavigateBack))
    }

    @Test
    fun `a new server state brings the button back`() = runTest {
        val (vm, _) = build(refused("document is assigned to another worker"))
        vm.load(DOC)
        vm.takeIntoWork()
        assertFalse(vm.uiState.value.canTakeIntoWork)

        // e.g. the administrator reassigned the document to this worker
        cached.value = loaded.copy(state = DocumentState.COLLECTING, version = 17)

        assertFalse(vm.uiState.value.lockRefused)
        assertEquals(DocumentState.COLLECTING, vm.uiState.value.document?.state)
        assertTrue(vm.uiState.value.canTakeIntoWork)
    }

    @Test
    fun `without a refusal a purged document does not close the screen`() = runTest {
        val (vm, _) = build(refused("x"))
        vm.load(DOC)
        val events = mutableListOf<DocumentDetailUiEvent>()
        backgroundScope.launch(mainDispatcher) { vm.uiEvents.collect { events += it } }

        cached.value = null

        assertFalse(events.contains(DocumentDetailUiEvent.NavigateBack))
    }

    private companion object {
        const val DOC = "DOC-140266"
    }
}
