package ua.com.programmer.pick.presentation.document

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.task.scanPromptRes
import ua.com.programmer.pick.presentation.task.rememberTaskDimens
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.domain.model.TaskStepTarget
import ua.com.programmer.pick.presentation.common.OfflineBanner
import ua.com.programmer.pick.presentation.task.GuidedTaskSession
import ua.com.programmer.pick.presentation.task.TaskMessageBanner
import ua.com.programmer.pick.presentation.task.TaskRetryBar
import ua.com.programmer.pick.presentation.task.TaskUiState

/**
 * The guided task's current step, docked under the document's own line list.
 * Kept to what the list above cannot say — the list already marks the step's
 * line with its progress and planned / counted quantities:
 *
 *  - the step title (the server puts the essentials there: the cell to go to,
 *    how many to take, where to put away) and the one-shot message. A step
 *    about no line with nothing to press shows no title at all — on a
 *    receipt "scan a product" is what the document screen already says;
 *  - the step's secondary actions in a menu that a tap on the title row
 *    opens, so the panel stays one row tall over the list. The main action
 *    is the swipe right on the marked line, as on the classic screen, and is
 *    shown as a button only when the step is about no single line (review,
 *    final); `cancel` lives on Back (pause and leave), never here.
 *
 * When the step sends its destination as data (`target`), the title row is
 * composed from it — "AA-1-1 → 4 шт" — instead of the server's sentence.
 *
 * The step's hint and rows are not repeated: the hint restates the title and
 * the rows are a text copy of the list.
 */
@Composable
fun GuidedStepPanel(
    state: TaskUiState,
    onAction: (TaskActionButton) -> Unit,
    onQtyEntry: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    // Unit of the step's document line, for the line composed from the
    // step's target ("AA-1-1 → 4 шт").
    targetUnit: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            OfflineBanner(isOffline = !state.isOnline)
            state.message?.let { TaskMessageBanner(message = it) }
            if (state.retryOperationId != null) {
                TaskRetryBar(onRetry = onRetry)
            }

            val step = state.step
            if (step == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
                return@Column
            }

            val onLine = step.line != null && !state.isFinished
            val shown = state.actions.filter { action ->
                when {
                    action.code == GuidedTaskSession.ACTION_CANCEL -> false
                    // The main action is the swipe on the marked line.
                    action.isPrimary && onLine -> false
                    else -> true
                }
            }
            val (main, secondary) = shown.partition { it.isPrimary || state.isFinished }
            val needsQty = state.expect == TaskExpect.QTY && !state.isFinished

            // A step about no line with nothing to press ("scan a product" on
            // a receipt) needs no title: the document itself is the prompt.
            val showTitle = onLine || state.isFinished || shown.isNotEmpty() || needsQty
            // The panel stays one line tall: the secondary actions open as a
            // menu on a tap anywhere on the title row.
            var menuOpen by remember { mutableStateOf(false) }
            val hasMenu = secondary.isNotEmpty()
            // A step that names its destination as data reads as one short
            // line; the server's sentence is the fallback.
            val targetLine = step.target?.let { composeTargetLine(it, targetUnit) }
            val title = (targetLine ?: step.title)?.takeIf { showTitle }
            // The composed line says where and how many, not what to scan —
            // the server's sentence did; say it under the line.
            val scanPrompt = scanPromptRes(state.expect)
                ?.takeIf { targetLine != null && title != null && !state.isFinished }
            val dimens = rememberTaskDimens()
            if (title != null || hasMenu) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (hasMenu) Modifier.clickable { menuOpen = true } else Modifier)
                            .heightIn(min = 48.dp)
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        // The one instruction on the screen: as large as the
                        // step title on the task screen.
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title.orEmpty(),
                                style = dimens.titleStyle,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (scanPrompt != null) {
                                Text(
                                    text = stringResource(scanPrompt),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (hasMenu) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.guided_more_actions_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = menuOpen && hasMenu,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        secondary.forEach { action ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = action.label,
                                        color = if (action.isDanger) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                enabled = state.canAct,
                                onClick = {
                                    menuOpen = false
                                    onAction(action)
                                },
                            )
                        }
                    }
                }
            }

            if (needsQty) {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    CompactButton(
                        label = state.qtyInput.ifEmpty { stringResource(R.string.task_enter_quantity) },
                        enabled = state.canAct,
                        onClick = onQtyEntry,
                    )
                }
            }

            main.forEach { action ->
                Button(
                    onClick = { onAction(action) },
                    enabled = state.canAct || state.isFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CompactButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.heightIn(min = 40.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "AA-1-1 → 4 шт" from a step's target: the cell, then the quantity with the
 * document line's unit. Either half may be missing; null when both are.
 */
internal fun composeTargetLine(target: TaskStepTarget, unit: String?): String? {
    val qty = target.qty?.let { q -> listOfNotNull(q.toString(), unit?.takeIf { it.isNotBlank() }).joinToString(" ") }
    return listOfNotNull(target.cell, qty).joinToString(" → ").takeIf { it.isNotEmpty() }
}
