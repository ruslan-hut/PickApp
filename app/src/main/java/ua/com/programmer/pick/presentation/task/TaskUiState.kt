package ua.com.programmer.pick.presentation.task

import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.domain.model.TaskMessage
import ua.com.programmer.pick.domain.model.TaskState
import ua.com.programmer.pick.domain.model.TaskStep

/**
 * Everything the guided-task screen renders. The step is the server's, verbatim
 * — nothing here interprets it beyond deciding which input panel to show.
 */
data class TaskUiState(
    val task: GuidedTask? = null,
    val step: TaskStep? = null,
    /** One-shot notice about the last action; cleared by the next response. */
    val message: TaskMessage? = null,
    val isLoading: Boolean = true,
    /** An action is in flight — inputs and further scans are refused. */
    val isSending: Boolean = false,
    /**
     * The operation id of an action that never reached the server. A *Retry*
     * reuses it verbatim so the server replays rather than re-applies.
     */
    val retryOperationId: String? = null,
    val qtyInput: String = "",
    val isOnline: Boolean = true,
    val errorCode: String? = null
) {
    val expect: TaskExpect get() = step?.expect ?: TaskExpect.NONE

    val isFinished: Boolean get() = task != null && task.state != TaskState.OPEN

    /** Inputs and buttons are live only online, idle and on an open task. */
    val canAct: Boolean
        get() = isOnline && !isSending && task != null && task.state == TaskState.OPEN

    val actions: List<TaskActionButton> get() = step?.actions.orEmpty()

    val primaryAction: TaskActionButton? get() = actions.firstOrNull { it.isPrimary }

    /** The quantity panel submits only a parsed, non-empty number. */
    val qtyValue: Long? get() = qtyInput.takeIf { it.isNotBlank() }?.toLongOrNull()
}
