package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.domain.model.GuidedTask
import ua.com.programmer.pick.domain.model.OpenTask
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.domain.model.TaskLineUpdate
import ua.com.programmer.pick.domain.model.TaskMessage
import ua.com.programmer.pick.domain.model.TaskRow
import ua.com.programmer.pick.domain.model.TaskState
import ua.com.programmer.pick.domain.model.TaskStep

/**
 * Wire → domain for the guided-task envelope. Nothing is interpreted: unknown
 * `expect` values fall back to NONE and unknown action codes are carried
 * through verbatim, so a new server-side step needs no app release.
 *
 * Returns null only when the envelope carries no `task` — a malformed response.
 */
fun DeviceDto.TaskResponse.toDomain(): GuidedTask? {
    val t = task ?: return null
    return GuidedTask(
        id = t.id,
        type = t.type,
        warehouseId = t.warehouseId?.takeIf { it.isNotBlank() },
        documentId = t.documentId?.takeIf { it.isNotBlank() },
        state = TaskState.fromWire(t.state),
        stepId = t.stepId?.takeIf { it.isNotBlank() },
        metricCategory = t.metricCategory?.takeIf { it.isNotBlank() },
        startedAt = t.startedAt,
        updatedAt = t.updatedAt,
        step = step?.toDomain(),
        message = message?.toDomain(),
        replayed = replayed,
        lineUpdates = lineUpdates?.map { it.toDomain() }.orEmpty(),
    )
}

private fun DeviceDto.TaskStep.toDomain(): TaskStep = TaskStep(
    id = id,
    title = title?.takeIf { it.isNotBlank() },
    expect = TaskExpect.fromWire(expect),
    rows = rows?.mapNotNull { row ->
        row.text?.takeIf { it.isNotBlank() }?.let {
            TaskRow(text = it, planned = row.planned, actual = row.actual, highlight = row.highlight)
        }
    }.orEmpty(),
    // An action without a label cannot be rendered as a button; the server
    // always sends one, so dropping it is better than showing a blank key.
    actions = actions?.mapNotNull { action ->
        action.label?.takeIf { it.isNotBlank() }?.let {
            TaskActionButton(code = action.code, label = it, style = action.style)
        }
    }.orEmpty(),
    hint = hint?.takeIf { it.isNotBlank() },
    lockInfo = lockInfo?.takeIf { it.isNotBlank() },
)

private fun DeviceDto.TaskMessage.toDomain(): TaskMessage? =
    text?.takeIf { it.isNotBlank() }?.let {
        TaskMessage(level = level?.takeIf { l -> l.isNotBlank() } ?: TaskMessage.LEVEL_INFO, text = it)
    }

private fun DeviceDto.TaskLineUpdate.toDomain(): TaskLineUpdate = TaskLineUpdate(
    lineKey = lineKey?.takeIf { it.isNotBlank() },
    lineNumber = lineNumber,
    actualQuantity = actualQuantity,
    isCompleted = isCompleted,
)

fun DeviceDto.OpenTask.toDomain(): OpenTask = OpenTask(
    id = id,
    type = type,
    documentId = documentId?.takeIf { it.isNotBlank() },
    stepTitle = stepTitle?.takeIf { it.isNotBlank() },
    startedAt = startedAt,
)

/**
 * The open-tasks endpoint answers full envelopes; the list only needs the
 * summary each row renders.
 */
fun DeviceDto.TaskResponse.toOpenTask(): OpenTask? {
    val t = task ?: return null
    return OpenTask(
        id = t.id,
        type = t.type,
        documentId = t.documentId?.takeIf { it.isNotBlank() },
        stepTitle = step?.title?.takeIf { it.isNotBlank() },
        startedAt = t.startedAt,
    )
}
