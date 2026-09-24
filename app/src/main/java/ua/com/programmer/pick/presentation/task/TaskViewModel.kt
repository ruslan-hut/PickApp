package ua.com.programmer.pick.presentation.task

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

/**
 * The standalone guided-task screen: tasks with no document of their own
 * (recount, placement, move, replenishment) and the receiving picker. The
 * step machine is [GuidedTaskSession]; this ViewModel only picks the task from
 * the navigation arguments and feeds the scanner in. A document's own guided
 * Collect / receiving runs inside the document screen instead.
 */
@HiltViewModel
class TaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    guidedTaskRepository: GuidedTaskRepository,
    documentRepository: DocumentRepository,
    private val barcodeService: BarcodeService,
    networkMonitor: NetworkMonitor,
    syncOrchestrator: SyncOrchestrator,
    debugJournal: DebugJournal,
) : ViewModel() {

    companion object {
        const val HEARTBEAT_INTERVAL_MS = GuidedTaskSession.HEARTBEAT_INTERVAL_MS
        const val ACTION_SCAN = GuidedTaskSession.ACTION_SCAN
        const val ACTION_CONFIRM = GuidedTaskSession.ACTION_CONFIRM
        const val ACTION_CANCEL = GuidedTaskSession.ACTION_CANCEL
        const val ACTION_DONE = GuidedTaskSession.ACTION_DONE
        const val ACTION_NO_STOCK = GuidedTaskSession.ACTION_NO_STOCK
        const val ACTION_MANUAL_CELL = GuidedTaskSession.ACTION_MANUAL_CELL
    }

    private val argTaskId: String? = savedStateHandle.get<String>(Screen.TASK_ID_ARG)?.takeIf { it.isNotBlank() }
    private val argType: String? = savedStateHandle.get<String>(Screen.TASK_TYPE_ARG)?.takeIf { it.isNotBlank() }
    private val argDocumentId: String? =
        savedStateHandle.get<String>(Screen.DOCUMENT_ID_ARG)?.takeIf { it.isNotBlank() }

    private val session = GuidedTaskSession(
        scope = viewModelScope,
        guidedTaskRepository = guidedTaskRepository,
        documentRepository = documentRepository,
        syncOrchestrator = syncOrchestrator,
        debugJournal = debugJournal,
        isOnline = networkMonitor.isOnline,
        hostDocumentId = argDocumentId,
    )

    val uiState: StateFlow<TaskUiState> = session.uiState
    val events: SharedFlow<TaskUiEvent> = session.events

    init {
        viewModelScope.launch {
            barcodeService.scannedBarcodes.collect { session.onScan(it.rawValue) }
        }
        session.open(taskId = argTaskId, type = argType, documentId = argDocumentId)
    }

    suspend fun heartbeat() = session.heartbeat()
    fun onLeaveScreen() = session.leave()
    fun onQtyInputChange(value: String) = session.onQtyInputChange(value)
    fun confirmQuantity() = session.confirmQuantity()
    fun submitManualCell(value: String) = session.submitManualCell(value)
    fun submitManualValue(value: String) = session.submitManualValue(value)
    fun onAction(code: String) = session.onAction(code)
    fun retryPending() = session.retryPending()
    fun dismissMessage() = session.dismissMessage()
}
