package ua.com.programmer.pick.presentation.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
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
 *  - the step's secondary actions as compact buttons. The main action is the
 *    swipe right on the marked line, as on the classic screen, and is shown as
 *    a button only when the step is about no single line (review, final);
 *    `cancel` lives on Back (pause and leave), never here.
 *
 * The step's hint and rows are not repeated: the hint restates the title and
 * the rows are a text copy of the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidedStepPanel(
    state: TaskUiState,
    onAction: (TaskActionButton) -> Unit,
    onQtyEntry: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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
            if (showTitle) {
                step.title?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }
            }

            if (secondary.isNotEmpty() || needsQty) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (needsQty) {
                        CompactButton(
                            label = state.qtyInput.ifEmpty { stringResource(R.string.task_enter_quantity) },
                            enabled = state.canAct,
                            onClick = onQtyEntry,
                        )
                    }
                    secondary.forEach { action ->
                        CompactButton(label = action.label, enabled = state.canAct, onClick = { onAction(action) })
                    }
                }
            } else {
                Box(modifier = Modifier.padding(bottom = 12.dp))
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
