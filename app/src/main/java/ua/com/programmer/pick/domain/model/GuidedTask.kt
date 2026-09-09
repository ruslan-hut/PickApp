package ua.com.programmer.pick.domain.model

/**
 * Domain shapes of the server-driven guided WMS tasks (cell recount, placement,
 * cell move, replenishment, guided Collect, receiving).
 *
 * The device is a renderer: the server owns the step machine, the route and
 * every text the worker reads (already in the tenant's locale). Nothing here is
 * persisted — a task is resumed from the login `open_tasks` list or from
 * `GET /device/tasks/open`, never from a local copy. The app must not branch on
 * [GuidedTask.type] or [TaskStep.id]; step-id families exist only for logs.
 */
data class GuidedTask(
    val id: String,
    val type: String,
    val warehouseId: String? = null,
    /** ERP external_id, on document-bound tasks only. */
    val documentId: String? = null,
    val state: TaskState,
    val stepId: String? = null,
    val metricCategory: String? = null,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val step: TaskStep? = null,
    /** One-shot notice about the last action; not part of the step. */
    val message: TaskMessage? = null,
    /** True when the server replayed an earlier response for the same operation id. */
    val replayed: Boolean = false,
    val lineUpdates: List<TaskLineUpdate> = emptyList()
) {
    val isFinished: Boolean get() = state != TaskState.OPEN
}

data class TaskStep(
    val id: String,
    val title: String? = null,
    val expect: TaskExpect = TaskExpect.NONE,
    val rows: List<TaskRow> = emptyList(),
    val actions: List<TaskActionButton> = emptyList(),
    val hint: String? = null,
    /** Muted line naming the lock this step holds, when the server sends one. */
    val lockInfo: String? = null
)

data class TaskRow(
    val text: String,
    val planned: Long? = null,
    val actual: Long? = null,
    /** Server marks rows that still need attention. */
    val highlight: Boolean = false
)

data class TaskActionButton(
    val code: String,
    val label: String,
    val style: String? = null
) {
    val isPrimary: Boolean get() = style == STYLE_PRIMARY
    val isDanger: Boolean get() = style == STYLE_DANGER

    companion object {
        const val STYLE_PRIMARY = "primary"
        const val STYLE_DANGER = "danger"
    }
}

data class TaskMessage(
    val level: String,
    val text: String
) {
    val isError: Boolean get() = level == LEVEL_ERROR
    val isInfo: Boolean get() = level == LEVEL_INFO

    companion object {
        const val LEVEL_INFO = "info"
        const val LEVEL_WARNING = "warning"
        const val LEVEL_ERROR = "error"
    }
}

/**
 * A document line the last action changed. Applied straight to the cached
 * document — matched by [lineKey] when both sides carry one, else by
 * [lineNumber] — without marking the row dirty: these are server-authored
 * values, not worker edits.
 */
data class TaskLineUpdate(
    val lineKey: String? = null,
    val lineNumber: Int,
    val actualQuantity: Double,
    val isCompleted: Boolean
)

/** Login / refresh summary of an unfinished task, for the continue-cancel offer. */
data class OpenTask(
    val id: String,
    val type: String,
    val documentId: String? = null,
    val stepTitle: String? = null,
    val startedAt: Long = 0
)

/** What the step captures. An unknown wire value is treated as [NONE] — scans
 *  are still forwarded, so a new server-side expect degrades to a scan prompt. */
enum class TaskExpect {
    CELL, PRODUCT, BATCH, QTY, DOCUMENT, NONE;

    companion object {
        fun fromWire(value: String?): TaskExpect = when (value?.lowercase()) {
            "cell" -> CELL
            "product" -> PRODUCT
            "batch" -> BATCH
            "qty" -> QTY
            "document" -> DOCUMENT
            else -> NONE
        }
    }
}

enum class TaskState {
    OPEN, DONE, CANCELLED;

    companion object {
        fun fromWire(value: String?): TaskState = when (value?.uppercase()) {
            "DONE" -> DONE
            "CANCELLED" -> CANCELLED
            else -> OPEN
        }
    }
}
