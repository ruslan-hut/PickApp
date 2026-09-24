package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.OpenTask

/**
 * The single caller of the guided-task endpoints. It owns the `operation_id`
 * per action and the retry that reuses it, so a dropped connection replays the
 * original response instead of applying a confirm twice.
 *
 * Guided actions are online-only: nothing here is ever queued in
 * `OutgoingOperationEntity`, and no task state is persisted on the device.
 * Resume comes from the login `open_tasks` list or from [refreshOpen].
 */
interface GuidedTaskRepository {

    /** The worker's unfinished tasks, from login and from [refreshOpen]. */
    val openTasks: StateFlow<List<OpenTask>>

    /** The task the worker is currently running, if any. */
    val active: StateFlow<GuidedTask?>

    /**
     * Start (or resume) a task: by [type] for a system task type, by
     * [documentId] — an ERP external_id — for a document-bound flow.
     */
    suspend fun start(type: String?, documentId: String?): TaskCallResult

    /** Current step of a task (resume after a restart). */
    suspend fun get(taskId: String): TaskCallResult

    /**
     * One action on the shown step. [operationId] must be a fresh UUID the
     * caller keeps across retries of the *same* action; pass null to have the
     * repository mint one.
     */
    suspend fun act(
        taskId: String,
        stepId: String,
        action: String,
        value: String? = null,
        quantity: Long? = null,
        operationId: String? = null,
        lineKey: String? = null,
        lineNumber: Int? = null,
    ): TaskCallResult

    /** Abandon a task; the server releases its cell / line locks. */
    suspend fun cancel(taskId: String): TaskCallResult

    /** Re-read the open-task list from the server. */
    suspend fun refreshOpen(): Boolean

    /** Forget the active task without touching the server (leaving the screen). */
    fun clearActive()
}

/**
 * Outcome of one task call. A [Failure] carries the server envelope code so the
 * caller can branch on FEATURE_DISABLED / GUIDED_OFF / LOCKED / … without
 * parsing the human-readable message; [code] is null when the call never
 * reached the server.
 */
sealed class TaskCallResult {
    data class Success(val task: GuidedTask) : TaskCallResult()
    data class Failure(val code: String?, val message: String?) : TaskCallResult()

    val taskOrNull: GuidedTask? get() = (this as? Success)?.task
}
