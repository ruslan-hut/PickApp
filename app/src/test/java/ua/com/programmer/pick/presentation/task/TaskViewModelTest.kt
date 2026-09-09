package ua.com.programmer.pick.presentation.task

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.core.scanner.BarcodeFormat
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.scanner.ScannedBarcode
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.domain.model.TaskLineUpdate
import ua.com.programmer.pick.domain.model.TaskState
import ua.com.programmer.pick.domain.model.TaskStep
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository
import ua.com.programmer.pick.domain.repository.TaskCallResult
import ua.com.programmer.pick.presentation.navigation.Screen

/**
 * The screen is a renderer: whatever the server sends becomes state, and one
 * action goes back. These tests pin the parts that are the app's own decision —
 * which call opens the task, what a scan turns into, when input is refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {

    // Own scheduler, so the ViewModel's init{} collectors never land on the
    // runTest scheduler and trip its uncompleted-coroutine check.
    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: GuidedTaskRepository
    private lateinit var documentRepository: DocumentRepository
    private lateinit var scans: MutableSharedFlow<ScannedBarcode>
    private lateinit var isOnline: MutableStateFlow<Boolean>

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        repository = mockk(relaxed = true)
        documentRepository = mockk(relaxed = true)
        scans = MutableSharedFlow(extraBufferCapacity = 4)
        isOnline = MutableStateFlow(true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a type argument starts a system task`() = runTest {
        coEvery { repository.start("CELL_RECOUNT", null) } returns success(step())

        val vm = build(type = "CELL_RECOUNT")

        coVerify(exactly = 1) { repository.start("CELL_RECOUNT", null) }
        assertEquals("rc_count", vm.uiState.value.step?.id)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `a task id argument resumes rather than starts`() = runTest {
        coEvery { repository.get("t1") } returns success(step())

        build(taskId = "t1")

        coVerify(exactly = 1) { repository.get("t1") }
        coVerify(exactly = 0) { repository.start(any(), any()) }
    }

    @Test
    fun `a document argument starts the document-bound task`() = runTest {
        coEvery { repository.start(null, "ERP-DOC-1") } returns success(step(), documentId = "ERP-DOC-1")

        build(documentId = "ERP-DOC-1")

        coVerify(exactly = 1) { repository.start(null, "ERP-DOC-1") }
    }

    @Test
    fun `a scan is forwarded raw with the shown step id`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step(expect = TaskExpect.PRODUCT))
        val vm = build(type = "CELL_RECOUNT")

        scans.emit(scan("4820000123456"))

        coVerify(exactly = 1) {
            repository.act("t1", "rc_count", "scan", "4820000123456", null, any())
        }
        assertNotNull(vm)
    }

    @Test
    fun `a scan while an action is in flight is dropped`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step(expect = TaskExpect.PRODUCT))
        // Never answers: the ViewModel stays in isSending for the whole test.
        coEvery { repository.act(any(), any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = build(type = "CELL_RECOUNT")

        scans.emit(scan("FIRST"))
        assertTrue(vm.uiState.value.isSending)
        scans.emit(scan("SECOND"))

        coVerify(exactly = 0) { repository.act(any(), any(), any(), "SECOND", any(), any()) }
    }

    @Test
    fun `offline refuses every action`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step(expect = TaskExpect.PRODUCT))
        val vm = build(type = "CELL_RECOUNT")
        isOnline.value = false

        scans.emit(scan("4820000123456"))
        vm.onAction("confirm")

        assertFalse(vm.uiState.value.canAct)
        coVerify(exactly = 0) { repository.act(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a quantity confirm sends the parsed number under the primary action code`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(
            step(expect = TaskExpect.QTY, actions = listOf(TaskActionButton("confirm", "Save count", "primary"))),
        )
        val vm = build(type = "CELL_RECOUNT")

        vm.onQtyInputChange("4a5")
        assertEquals("45", vm.uiState.value.qtyInput)
        vm.confirmQuantity()

        coVerify(exactly = 1) { repository.act("t1", "rc_count", "confirm", null, 45L, any()) }
    }

    @Test
    fun `an empty quantity is not sent`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step(expect = TaskExpect.QTY))
        val vm = build(type = "CELL_RECOUNT")

        vm.confirmQuantity()

        coVerify(exactly = 0) { repository.act(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a manual cell is sent as manual_cell with the typed value`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step(expect = TaskExpect.CELL))
        val vm = build(type = "CELL_RECOUNT")

        vm.submitManualCell("  A-01-02  ")

        coVerify(exactly = 1) { repository.act("t1", "rc_count", "manual_cell", "A-01-02", null, any()) }
    }

    @Test
    fun `a server message is surfaced but a replay is not`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(
            step(),
            message = ua.com.programmer.pick.domain.model.TaskMessage("warning", "Ivan is counting this cell"),
            replayed = true,
        )
        val vm = build(type = "CELL_RECOUNT")

        assertEquals("Ivan is counting this cell", vm.uiState.value.message?.text)
        // `replayed` is deliberately invisible: the worker asked once.
        assertTrue(vm.uiState.value.task?.replayed == true)
    }

    @Test
    fun `a stale-step response simply replaces the state`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step())
        coEvery { repository.act(any(), any(), any(), any(), any(), any()) } returns success(
            step(id = "rc_scan_cell", expect = TaskExpect.CELL),
            message = ua.com.programmer.pick.domain.model.TaskMessage("warning", "step moved on"),
        )
        val vm = build(type = "CELL_RECOUNT")

        vm.onAction("confirm")

        assertEquals("rc_scan_cell", vm.uiState.value.step?.id)
        assertEquals(TaskExpect.CELL, vm.uiState.value.expect)
        assertEquals("", vm.uiState.value.qtyInput)
    }

    @Test
    fun `a DONE response navigates home for a system task`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step())
        coEvery { repository.act(any(), any(), any(), any(), any(), any()) } returns
            success(step(id = "rc_done"), state = TaskState.DONE)
        val vm = build(type = "CELL_RECOUNT")
        // events has no replay: subscribe before the action that emits.
        val events = mutableListOf<TaskUiEvent>()
        backgroundScope.launch(mainDispatcher) { vm.events.collect { events += it } }

        vm.onAction("confirm")
        vm.onAction("done")

        assertEquals(listOf(TaskUiEvent.NavigateHome), events)
    }

    @Test
    fun `a failure with no server code offers a retry instead of leaving`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step(expect = TaskExpect.PRODUCT))
        coEvery { repository.act(any(), any(), any(), any(), any(), any()) } returns
            TaskCallResult.Failure(null, "no response")
        val vm = build(type = "CELL_RECOUNT")

        scans.emit(scan("X"))

        assertNotNull(vm.uiState.value.retryOperationId)
    }

    @Test
    fun `a retry repeats the same operation id`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step(expect = TaskExpect.PRODUCT))
        coEvery { repository.act(any(), any(), any(), any(), any(), any()) } returns
            TaskCallResult.Failure(null, "no response")
        val vm = build(type = "CELL_RECOUNT")

        scans.emit(scan("X"))
        val operationId = vm.uiState.value.retryOperationId
        assertNotNull(operationId)

        vm.retryPending()

        coVerify(exactly = 2) { repository.act("t1", "rc_count", "scan", "X", null, operationId!!) }
    }

    @Test
    fun `a server verdict leaves the screen`() = runTest {
        coEvery { repository.start(any(), any()) } returns TaskCallResult.Failure("GUIDED_OFF", "worked classically")

        val vm = build(documentId = "ERP-DOC-1")

        assertEquals("GUIDED_OFF", vm.uiState.value.errorCode)
        assertNull(vm.uiState.value.step)
    }

    @Test
    fun `line updates on a document task are written to the cached document`() = runTest {
        val updates = listOf(
            TaskLineUpdate(lineKey = "K1", lineNumber = 1, actualQuantity = 8.0, isCompleted = true),
        )
        coEvery { repository.start(null, "ERP-DOC-1") } returns
            success(step(), documentId = "ERP-DOC-1", lineUpdates = updates)

        build(documentId = "ERP-DOC-1")

        coVerify(exactly = 1) { documentRepository.applyServerLineUpdates("ERP-DOC-1", updates) }
    }

    @Test
    fun `a system task never touches the document cache`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(step())

        build(type = "CELL_RECOUNT")

        coVerify(exactly = 0) { documentRepository.applyServerLineUpdates(any(), any()) }
    }

    @Test
    fun `an envelope without line updates writes nothing`() = runTest {
        coEvery { repository.start(null, "ERP-DOC-1") } returns success(step(), documentId = "ERP-DOC-1")

        build(documentId = "ERP-DOC-1")

        coVerify(exactly = 0) { documentRepository.applyServerLineUpdates(any(), any()) }
    }

    @Test
    fun `an error-level step message buzzes, a warning does not`() = runTest {
        coEvery { repository.start(any(), any()) } returns success(
            step(),
            message = ua.com.programmer.pick.domain.model.TaskMessage("warning", "shelf is short"),
        )
        val warned = build(type = "CELL_RECOUNT")
        val warnEvents = mutableListOf<TaskUiEvent>()
        backgroundScope.launch(mainDispatcher) { warned.events.collect { warnEvents += it } }
        coEvery { repository.act(any(), any(), any(), any(), any(), any()) } returns success(
            step(),
            message = ua.com.programmer.pick.domain.model.TaskMessage("warning", "still short"),
        )
        warned.onAction("confirm")
        assertTrue(warnEvents.none { it is TaskUiEvent.VibrateError })

        coEvery { repository.act(any(), any(), any(), any(), any(), any()) } returns success(
            step(),
            message = ua.com.programmer.pick.domain.model.TaskMessage("error", "wrong cell"),
        )
        warned.onAction("confirm")
        assertTrue(warnEvents.any { it is TaskUiEvent.VibrateError })
    }

    // --- helpers ---

    private fun build(
        taskId: String? = null,
        type: String? = null,
        documentId: String? = null,
    ): TaskViewModel {
        val handle = SavedStateHandle(
            buildMap {
                taskId?.let { put(Screen.TASK_ID_ARG, it) }
                type?.let { put(Screen.TASK_TYPE_ARG, it) }
                documentId?.let { put(Screen.DOCUMENT_ID_ARG, it) }
            },
        )
        val barcodeService = mockk<BarcodeService>(relaxed = true) {
            every { scannedBarcodes } returns scans
        }
        val networkMonitor = mockk<NetworkMonitor>(relaxed = true) {
            every { this@mockk.isOnline } returns this@TaskViewModelTest.isOnline
        }
        return TaskViewModel(
            savedStateHandle = handle,
            guidedTaskRepository = repository,
            documentRepository = documentRepository,
            barcodeService = barcodeService,
            networkMonitor = networkMonitor,
            syncOrchestrator = mockk<SyncOrchestrator>(relaxed = true),
            debugJournal = mockk<DebugJournal>(relaxed = true),
        )
    }

    private fun step(
        id: String = "rc_count",
        expect: TaskExpect = TaskExpect.QTY,
        actions: List<TaskActionButton> = listOf(TaskActionButton("confirm", "Save", "primary")),
    ) = TaskStep(id = id, title = "Cell A-01-02", expect = expect, actions = actions)

    private fun success(
        step: TaskStep,
        state: TaskState = TaskState.OPEN,
        documentId: String? = null,
        message: ua.com.programmer.pick.domain.model.TaskMessage? = null,
        replayed: Boolean = false,
        lineUpdates: List<TaskLineUpdate> = emptyList(),
    ) = TaskCallResult.Success(
        GuidedTask(
            id = "t1",
            type = "CELL_RECOUNT",
            documentId = documentId,
            state = state,
            stepId = step.id,
            step = step,
            message = message,
            replayed = replayed,
            lineUpdates = lineUpdates,
        ),
    )

    private fun scan(raw: String) = ScannedBarcode(
        rawValue = raw,
        format = BarcodeFormat.EAN_13,
        gs1Data = null,
    )
}
