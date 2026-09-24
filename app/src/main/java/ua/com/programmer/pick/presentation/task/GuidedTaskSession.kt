package ua.com.programmer.pick.presentation.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.debug.DebugEventType
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository
import ua.com.programmer.pick.domain.repository.TaskCallResult
import java.util.UUID

/**
 * One guided task as the terminal drives it: it renders whatever step the
 * server sends and posts one action back — there is no per-flow branch here,
 * and no branch on step ids or task type.
 *
 * Hosted by [TaskViewModel] (the standalone task screen: recount, placement,
 * move, replenishment) and by the document detail screen, which runs a
 * document's guided Collect / receiving in place over its own line list. The
 * host feeds scans in ([onScan]) and drives the heartbeat; the session owns the
 * state, the retry-safe dispatch and the line mirror.
 *
 * Heartbeat (D4): a guided document is deliberately absent from the
 * orchestrator's `heldStageLocks`, so its active poll never runs for a task.
 * While the host screen is resumed it calls [heartbeat] on the orchestrator's
 * 8 s cadence, which extends the server-side cell / line locks and keeps the
 * cached document fresh.
 */
class GuidedTaskSession(
    private val scope: CoroutineScope,
    private val guidedTaskRepository: GuidedTaskRepository,
    private val documentRepository: DocumentRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val debugJournal: DebugJournal,
    isOnline: Flow<Boolean>,
    /** Document id the host was opened for, used when a task never opened. */
    private val hostDocumentId: String? = null,
) {

    companion object {
        /** Mirrors the orchestrator's active-poll cadence (D4). */
        const val HEARTBEAT_INTERVAL_MS = 8_000L

        const val ACTION_SCAN = "scan"
        const val ACTION_CONFIRM = "confirm"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_DONE = "done"
        const val ACTION_NO_STOCK = "no_stock"
        const val ACTION_MANUAL_CELL = "manual_cell"
        const val ACTION_SET_QUANTITY = "set_quantity"

        private const val TAG = "GuidedTaskSession"
    }

    private val _uiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TaskUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TaskUiEvent> = _events.asSharedFlow()

    /**
     * The document (external id) whose cached lines an answer just rewrote.
     * The document screen re-reads its lines on it, so progress shows on every
     * scan without a sync round-trip.
     */
    private val _linesChanged = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val linesChanged: SharedFlow<String> = _linesChanged.asSharedFlow()

    /** The action a *Retry* would repeat, kept whole so the id is reused. */
    private var pendingAction: PendingAction? = null

    init {
        scope.launch {
            isOnline.collect { online -> _uiState.update { it.copy(isOnline = online) } }
        }
    }

    // --- Lifecycle ---

    /**
     * Opens the task: resume by [taskId], or start (or resume the worker's open
     * one) for [documentId] / [type].
     */
    fun open(taskId: String? = null, type: String? = null, documentId: String? = null) {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = when {
                taskId != null -> guidedTaskRepository.get(taskId)
                documentId != null -> guidedTaskRepository.start(null, documentId)
                type != null -> guidedTaskRepository.start(type, null)
                else -> TaskCallResult.Failure(null, "no task argument")
            }
            _uiState.update { it.copy(isLoading = false) }
            handle(result, isOpening = true)
        }
    }

    /** Called while the host screen is resumed; see D4. */
    suspend fun heartbeat() {
        if (!_uiState.value.isOnline) return
        try {
            syncOrchestrator.requestDeltaSyncIfStale(HEARTBEAT_INTERVAL_MS)
        } catch (e: Exception) {
            AppLog.w(TAG, "heartbeat sync failed: ${e.message}")
        }
    }

    /** Back / screen closed: the task stays open on the server (only `cancel` closes it). */
    fun leave() {
        val task = _uiState.value.task ?: return
        debugJournal.log(
            eventType = DebugEventType.TASK_CLOSED,
            message = "screen left, task stays open",
            documentId = journalId(task),
        )
        guidedTaskRepository.clearActive()
    }

    // --- Input ---

    /**
     * A scan from the host. Forwarded raw as `action: scan` regardless of
     * `expect` — the server resolves the code, so the app never inspects it or
     * enriches it from the product catalogue.
     */
    fun onScan(rawValue: String) {
        val state = _uiState.value
        if (!state.canAct) {
            debugJournal.log(
                eventType = DebugEventType.TASK_SCAN_DROPPED,
                message = "scan dropped (sending=${state.isSending} online=${state.isOnline})",
                documentId = state.task?.let { journalId(it) },
                severity = DebugJournal.SEVERITY_WARN,
            )
            return
        }
        send(action = ACTION_SCAN, value = rawValue)
    }

    fun onQtyInputChange(value: String) {
        // Digits only: `quantity` is integer pieces on the wire.
        _uiState.update { it.copy(qtyInput = value.filter(Char::isDigit)) }
    }

    /** The primary action of an `expect: qty` step. */
    fun confirmQuantity() {
        val state = _uiState.value
        val quantity = state.qtyValue ?: return
        val code = state.primaryAction?.code ?: ACTION_CONFIRM
        send(action = code, quantity = quantity)
    }

    /** A cell address typed because the label is unreadable. */
    fun submitManualCell(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        send(action = ACTION_MANUAL_CELL, value = trimmed)
    }

    /** A document number typed on the receiving picker; the server matches it like a scan. */
    fun submitManualValue(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        send(action = ACTION_SCAN, value = trimmed)
    }

    /**
     * Any action button. `cancel` / `no_stock` are confirmed by the screen
     * before they get here; `done` closes the task view without waiting.
     * `manual_cell` is never sent from here: it needs the typed address, which
     * only [submitManualCell] has — without it the server answers "cell not
     * found".
     */
    fun onAction(code: String) {
        if (code == ACTION_DONE) {
            finish()
            return
        }
        if (code == ACTION_MANUAL_CELL) {
            AppLog.w(TAG, "manual_cell without a value ignored")
            return
        }
        val quantity = if (_uiState.value.expect == TaskExpect.QTY) {
            _uiState.value.qtyValue
        } else {
            null
        }
        send(action = code, quantity = quantity)
    }

    /**
     * +/− or a typed quantity on the step's line (`step.line.adjustable`):
     * the line's new count, absolute. Not one of the step's buttons — the
     * server accepts it on every adjustable step.
     */
    fun setQuantity(quantity: Long, lineKey: String? = null, lineNumber: Int? = null) {
        val step = _uiState.value.step ?: return
        if (quantity < 0) return
        // A named line needs a step that takes any line; otherwise the step's own.
        val allowed = if (lineKey != null || lineNumber != null) step.adjustable else step.line?.adjustable == true
        if (!allowed) return
        send(action = ACTION_SET_QUANTITY, quantity = quantity, lineKey = lineKey, lineNumber = lineNumber)
    }

    /** Repeats the action that never reached the server, with its original id. */
    fun retryPending() {
        val pending = pendingAction ?: return
        dispatch(pending)
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // --- Internals ---

    private fun send(
        action: String,
        value: String? = null,
        quantity: Long? = null,
        lineKey: String? = null,
        lineNumber: Int? = null,
    ) {
        val state = _uiState.value
        if (!state.canAct) return
        val task = state.task ?: return
        val stepId = state.step?.id ?: return
        dispatch(
            PendingAction(
                taskId = task.id,
                stepId = stepId,
                action = action,
                value = value,
                quantity = quantity,
                operationId = UUID.randomUUID().toString(),
                lineKey = lineKey,
                lineNumber = lineNumber,
            ),
        )
    }

    private fun dispatch(pending: PendingAction) {
        pendingAction = pending
        scope.launch {
            _uiState.update { it.copy(isSending = true, retryOperationId = null) }
            val result = guidedTaskRepository.act(
                taskId = pending.taskId,
                stepId = pending.stepId,
                action = pending.action,
                value = pending.value,
                quantity = pending.quantity,
                operationId = pending.operationId,
                lineKey = pending.lineKey,
                lineNumber = pending.lineNumber,
            )
            _uiState.update { it.copy(isSending = false) }
            handle(result, cancelled = pending.action == ACTION_CANCEL)
        }
    }

    private suspend fun handle(
        result: TaskCallResult,
        isOpening: Boolean = false,
        cancelled: Boolean = false,
    ) {
        when (result) {
            is TaskCallResult.Success -> {
                pendingAction = null
                applyLineUpdates(result.task)
                render(result.task)
                // A refusal the worker must notice without looking up from the
                // shelf. Warnings and acknowledgements stay silent.
                if (result.task.message?.isError == true) {
                    _events.emit(TaskUiEvent.VibrateError)
                }
                // A cancel closes the task: leave as soon as the server confirms.
                if (cancelled) finish()
            }

            is TaskCallResult.Failure -> {
                _uiState.update { it.copy(errorCode = result.code) }
                // No code means the request never got an answer — offer a retry
                // that reuses the operation id instead of leaving the screen.
                if (result.code == null && !isOpening && pendingAction != null) {
                    _uiState.update { it.copy(retryOperationId = pendingAction?.operationId) }
                    _events.emit(TaskUiEvent.VibrateError)
                    return
                }
                emitError(result.code, result.message)
                if (isOpening || result.code.leavesTask()) finish(force = true)
            }
        }
    }

    /**
     * D5: the server mirrors every confirmed line into the document, so the
     * updates go straight to Room. The document screen follows them live via
     * [linesChanged], with no sync round-trip and no dirty flag.
     */
    private suspend fun applyLineUpdates(task: GuidedTask) {
        val documentId = task.documentId ?: return
        if (task.lineUpdates.isEmpty()) return
        val applied = try {
            documentRepository.applyServerLineUpdates(documentId, task.lineUpdates)
        } catch (e: Exception) {
            AppLog.w(TAG, "applying task line updates failed: ${e.message}")
            return
        }
        debugJournal.log(
            eventType = DebugEventType.TASK_LINE_UPDATES_APPLIED,
            message = "$applied of ${task.lineUpdates.size} line(s) written",
            documentId = documentId,
            severity = if (applied < task.lineUpdates.size) {
                DebugJournal.SEVERITY_WARN
            } else {
                DebugJournal.SEVERITY_INFO
            },
        )
        _linesChanged.emit(documentId)
    }

    private fun render(task: GuidedTask) {
        _uiState.update {
            it.copy(
                task = task,
                step = task.step,
                // `replayed` is not surfaced: the worker asked once and the
                // answer is the same one, so it is not news.
                message = task.message,
                // A new step always starts from an empty quantity field.
                qtyInput = if (task.step?.id != it.step?.id) "" else it.qtyInput,
                errorCode = null,
                retryOperationId = null,
            )
        }
    }

    private fun finish(force: Boolean = false) {
        val state = _uiState.value
        // A task that never opened has no document of its own; fall back to the
        // host's document so a refused document task returns to the list, not Home.
        val documentId = state.task?.documentId ?: hostDocumentId
        scope.launch {
            if (!force) {
                debugJournal.log(
                    eventType = DebugEventType.TASK_CLOSED,
                    message = "task finished",
                    documentId = state.task?.let { journalId(it) },
                )
            }
            guidedTaskRepository.clearActive()
            if (documentId != null) {
                _events.emit(TaskUiEvent.NavigateToDocuments(documentId))
            } else {
                _events.emit(TaskUiEvent.NavigateHome)
            }
        }
    }

    private suspend fun emitError(code: String?, serverMessage: String?) {
        // A document task takes the Collect lock through the same path as
        // `POST /device/documents/{id}/lock`, whose refusals are generic:
        // CONFLICT (not in the Collect stage) and FORBIDDEN (assigned to
        // another worker). Both mean "this document is not yours to start".
        val isDocumentTask = hostDocumentId != null || _uiState.value.task?.documentId != null
        val toast = when (code) {
            "FEATURE_DISABLED", "WMS_WAREHOUSE_DISABLED" -> TaskToastMessage.FEATURE_DISABLED
            "GUIDED_OFF" -> TaskToastMessage.GUIDED_OFF
            "NO_WAREHOUSE" -> TaskToastMessage.NO_WAREHOUSE
            "NOT_FOUND" -> TaskToastMessage.NOT_FOUND
            "FORBIDDEN" -> if (isDocumentTask) TaskToastMessage.WRONG_STATE else TaskToastMessage.FORBIDDEN
            "CONFLICT" -> if (isDocumentTask) TaskToastMessage.WRONG_STATE else TaskToastMessage.GENERIC
            "WRONG_STATE", "DOCUMENT_LOCKED", "DOCUMENT_WAREHOUSE" -> TaskToastMessage.WRONG_STATE
            "DEMO_UNSUPPORTED" -> TaskToastMessage.DEMO
            else -> TaskToastMessage.GENERIC
        }
        // LOCKED and every other in-step refusal come back with the server's own
        // wording; show it rather than an app string.
        _events.emit(TaskUiEvent.ShowToast(toast, serverMessage.takeIf { code == "LOCKED" }))
        _events.emit(TaskUiEvent.VibrateError)
    }

    private fun journalId(task: GuidedTask): String = task.documentId ?: "task:${task.id}"

    private data class PendingAction(
        val taskId: String,
        val stepId: String,
        val action: String,
        val value: String?,
        val quantity: Long?,
        val operationId: String,
        val lineKey: String? = null,
        val lineNumber: Int? = null,
    )
}

/** Codes that make the current task view meaningless — leave it and go back. */
private fun String?.leavesTask(): Boolean = this in setOf(
    "FEATURE_DISABLED",
    "WMS_WAREHOUSE_DISABLED",
    "GUIDED_OFF",
    "NO_WAREHOUSE",
    "NOT_FOUND",
    "FORBIDDEN",
    "WRONG_STATE",
    "DOCUMENT_LOCKED",
    "DOCUMENT_WAREHOUSE",
    "DEMO_UNSUPPORTED",
)
