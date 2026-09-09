package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.debug.DebugEventType
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toOpenTask
import ua.com.programmer.pick.data.remote.transport.MessageParser
import ua.com.programmer.pick.data.remote.transport.SyncMessage
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.OpenTask
import ua.com.programmer.pick.domain.model.TaskState
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository
import ua.com.programmer.pick.domain.repository.TaskCallResult
import kotlinx.coroutines.CoroutineDispatcher
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guided-task calls over the transport seam.
 *
 * Retry policy (D2): a transport-level failure — no response, or a failure that
 * carries no server error code — is retried up to [MAX_ATTEMPTS] times with the
 * **same** `operation_id`, so the server replays the original response instead
 * of applying a confirm twice. A response that *does* carry a server code is a
 * decision, not a hiccup: it is returned to the caller unretried.
 *
 * Nothing is persisted. The open-task list is seeded from the login response
 * and refreshed on demand; a task that reaches DONE / CANCELLED drops out of it.
 */
@Singleton
class GuidedTaskRepositoryImpl @Inject constructor(
    private val transport: SyncTransport,
    private val messageParser: MessageParser,
    private val debugJournal: DebugJournal,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : GuidedTaskRepository {

    private companion object {
        const val TAG = "GuidedTaskRepository"
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _openTasks = MutableStateFlow<List<OpenTask>>(emptyList())
    override val openTasks: StateFlow<List<OpenTask>> = _openTasks.asStateFlow()

    private val _active = MutableStateFlow<GuidedTask?>(null)
    override val active: StateFlow<GuidedTask?> = _active.asStateFlow()

    init {
        // The login response is the first source of open tasks; a warehouse
        // without the module simply never sends any.
        transport.userAuthState
            .onEach { state ->
                when (state) {
                    is UserAuthState.Authenticated ->
                        _openTasks.value = state.openTasks?.map { it.toDomain() }.orEmpty()
                    is UserAuthState.NotAuthenticated -> {
                        _openTasks.value = emptyList()
                        _active.value = null
                    }
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    override suspend fun start(type: String?, documentId: String?): TaskCallResult {
        debugJournal.log(
            eventType = DebugEventType.TASK_START,
            message = "start type=${type ?: "-"} document=${documentId ?: "-"}",
            documentId = documentId,
        )
        return call(documentId = documentId) {
            SyncMessage.TaskStart(newId(), now(), taskType = type, documentId = documentId)
        }
    }

    override suspend fun get(taskId: String): TaskCallResult =
        call(journalKey = taskId) { SyncMessage.TaskGet(newId(), now(), taskId) }

    override suspend fun act(
        taskId: String,
        stepId: String,
        action: String,
        value: String?,
        quantity: Long?,
        operationId: String?,
    ): TaskCallResult {
        val opId = operationId ?: UUID.randomUUID().toString()
        debugJournal.log(
            eventType = DebugEventType.TASK_ACTION_SENT,
            message = "action=$action step=$stepId",
            documentId = journalDocumentId(taskId),
            payload = mapOf("task_id" to taskId, "operation_id" to opId, "value" to value, "quantity" to quantity),
        )
        return call(journalKey = taskId) {
            SyncMessage.TaskAction(newId(), now(), taskId, opId, stepId, action, value, quantity)
        }
    }

    override suspend fun cancel(taskId: String): TaskCallResult {
        debugJournal.log(
            eventType = DebugEventType.TASK_CANCEL,
            message = "cancel task",
            documentId = journalDocumentId(taskId),
        )
        return call(journalKey = taskId) {
            SyncMessage.TaskCancel(newId(), now(), taskId, UUID.randomUUID().toString())
        }
    }

    override suspend fun refreshOpen(): Boolean {
        val result = transport.sendAndAwait(
            SyncMessage.TaskOpen(newId(), now()),
            SyncMessage.TaskOpenResult::class.java,
        )
        if (result == null || !result.success) {
            AppLog.w(TAG, "open tasks refresh failed: ${result?.errorCode ?: "no response"}")
            return false
        }
        _openTasks.value = result.tasks.mapNotNull { it.toOpenTask() }
        return true
    }

    override fun clearActive() {
        _active.value = null
    }

    /**
     * Sends [build]'s message, retrying the identical message — same
     * `operation_id` — while the transport fails to produce a server answer.
     */
    private suspend fun call(
        journalKey: String? = null,
        documentId: String? = null,
        build: () -> SyncMessage,
    ): TaskCallResult {
        val message = build()
        var lastCode: String? = null
        var lastMessage: String? = null

        for (attempt in 0 until MAX_ATTEMPTS) {
            val result = transport.sendAndAwait(message, SyncMessage.TaskResult::class.java)

            if (result != null && result.success) {
                val task = result.response?.toDomain()
                    ?: return TaskCallResult.Failure(null, "malformed task response")
                adopt(task)
                debugJournal.log(
                    eventType = DebugEventType.TASK_ACTION_RESULT,
                    message = "step=${task.stepId ?: "-"} state=${task.state}",
                    documentId = task.documentId ?: journalKey?.let { "task:$it" },
                    payload = mapOf(
                        "replayed" to task.replayed,
                        "line_updates" to task.lineUpdates.size,
                        "message" to task.message?.level,
                    ),
                )
                return TaskCallResult.Success(task)
            }

            lastCode = result?.errorCode
            lastMessage = result?.errorMessage ?: "no response"

            // A server verdict is final; only a transport hiccup is retried.
            if (result != null && lastCode != null) break

            debugJournal.log(
                eventType = DebugEventType.TASK_ACTION_FAILED,
                message = "attempt ${attempt + 1}/$MAX_ATTEMPTS: ${lastMessage ?: "failed"}",
                documentId = documentId ?: journalKey?.let { "task:$it" },
                severity = DebugJournal.SEVERITY_WARN,
            )
            if (attempt < MAX_ATTEMPTS - 1) {
                debugJournal.log(
                    eventType = DebugEventType.TASK_ACTION_RETRY,
                    message = "retrying with the same operation_id",
                    documentId = documentId ?: journalKey?.let { "task:$it" },
                )
                delay(RETRY_DELAYS_MS[attempt])
            }
        }

        AppLog.w(TAG, "task call failed: code=$lastCode message=$lastMessage")
        return TaskCallResult.Failure(lastCode, lastMessage)
    }

    /**
     * A finished task leaves the unfinished list at once, so Home never offers
     * *Continue* on a task the worker just closed. An open one is picked up by
     * the next [refreshOpen] — Home runs one on resume.
     */
    private fun adopt(task: GuidedTask) {
        _active.value = task
        if (task.state != TaskState.OPEN) {
            _openTasks.value = _openTasks.value.filterNot { it.id == task.id }
        }
    }

    private fun journalDocumentId(taskId: String): String =
        _active.value?.takeIf { it.id == taskId }?.documentId ?: "task:$taskId"

    private fun newId(): String = messageParser.generateMessageId()
    private fun now(): String = messageParser.getCurrentTimestamp()
}
