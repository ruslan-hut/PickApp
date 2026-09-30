package ua.com.programmer.pick.presentation.task

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.domain.model.TaskMessage
import ua.com.programmer.pick.domain.model.TaskRow
import ua.com.programmer.pick.domain.model.TaskStepTarget
import ua.com.programmer.pick.presentation.common.PickElevatedCard
import ua.com.programmer.pick.ui.theme.CardShape

/**
 * Sizes for the task screen. The layout is designed for a full-size terminal
 * screen; a short one (Memor K: 320 × 533 dp) gets tighter gutters, a smaller
 * step title and 48 dp keys so the step, its rows and the buttons still fit.
 */
@Immutable
data class TaskDimens(
    val compact: Boolean,
    val gutter: Dp,
    val actionHeight: Dp,
    val titleStyle: TextStyle,
    val qtyStyle: TextStyle,
    val hintStyle: TextStyle,
)

/** Below this height the screen is laid out compact. */
private const val COMPACT_HEIGHT_DP = 640

@Composable
fun rememberTaskDimens(): TaskDimens {
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
    val typography = MaterialTheme.typography
    return remember(compact, typography) {
        if (compact) {
            TaskDimens(true, 12.dp, 48.dp, typography.titleLarge, typography.titleLarge, typography.bodyMedium)
        } else {
            TaskDimens(false, 16.dp, 56.dp, typography.headlineSmall, typography.headlineSmall, typography.bodyLarge)
        }
    }
}

/**
 * The server's one-shot notice about the last action. Colour follows the level;
 * the text is server-authored and shown verbatim.
 */
@Composable
fun TaskMessageBanner(
    message: TaskMessage,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (message.level) {
        TaskMessage.LEVEL_ERROR ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        TaskMessage.LEVEL_WARNING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}

/**
 * The step itself, like the document's header card: what to do now in large
 * type, the destination when the server names one, the server's hint, and
 * which code the scanner is waiting for.
 */
@Composable
fun TaskStepCard(
    title: String,
    target: TaskStepTarget?,
    hint: String?,
    lockInfo: String?,
    expect: TaskExpect,
    dimens: TaskDimens,
    modifier: Modifier = Modifier,
) {
    PickElevatedCard(
        modifier = modifier.padding(horizontal = dimens.gutter, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(if (dimens.compact) 6.dp else 8.dp),
        ) {
            Text(
                text = title,
                style = dimens.titleStyle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            target?.let { t ->
                val qty = t.qty?.toString()
                val line = listOfNotNull(t.cell, qty).joinToString("  →  ")
                if (line.isNotEmpty()) {
                    Text(
                        text = line,
                        style = dimens.qtyStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = dimens.hintStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!lockInfo.isNullOrBlank()) {
                Text(
                    text = lockInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            scanPromptRes(expect)?.let { res -> TaskScanChip(text = stringResource(res)) }
        }
    }
}

@StringRes
internal fun scanPromptRes(expect: TaskExpect): Int? = when (expect) {
    TaskExpect.CELL -> R.string.task_scan_cell_prompt
    TaskExpect.PRODUCT -> R.string.task_scan_product_prompt
    TaskExpect.BATCH -> R.string.task_scan_batch_prompt
    TaskExpect.DOCUMENT -> R.string.task_scan_document_prompt
    else -> null
}

/** What the scanner is waiting for — the scanner is always live, so this only prompts. */
@Composable
private fun TaskScanChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * One context row of the step, drawn like a document line: the text, and the
 * counts as "actual / planned" in large figures. A highlighted row is the one
 * the step is about.
 */
@Composable
fun TaskRowItem(
    row: TaskRow,
    dimens: TaskDimens,
    modifier: Modifier = Modifier,
    // Set for a selectable row (row.value != null) when the worker can act.
    onClick: (() -> Unit)? = null,
) {
    val highlight = row.highlight
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.gutter, vertical = 4.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CardShape,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            width = if (highlight) 2.dp else 1.dp,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.gutter, vertical = if (dimens.compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TaskRowQuantities(row = row, dimens = dimens)
        }
    }
}

@Composable
private fun TaskRowQuantities(row: TaskRow, dimens: TaskDimens) {
    val main = row.actual ?: row.planned ?: return
    val color = if (row.highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Spacer(modifier = Modifier.width(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = main.toString(),
            style = dimens.qtyStyle,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        if (row.actual != null && row.planned != null) {
            Text(
                text = " / ${row.planned}",
                style = MaterialTheme.typography.titleMedium,
                color = color.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/**
 * The step's keys, docked like the document's bottom bar: secondary actions
 * two to a row in the server's order, the quantity field of an `expect: qty`
 * step, and the primary action full width at the bottom, under the thumb.
 * `cancel` is not here — it lives in the top bar's menu, as in the document
 * screen, so a big red key never sits next to the everyday ones.
 */
@Composable
fun TaskBottomBar(
    primary: TaskActionButton?,
    secondary: List<TaskActionButton>,
    showQtyField: Boolean,
    qtyInput: String,
    enabled: Boolean,
    isSending: Boolean,
    dimens: TaskDimens,
    onQtyEntry: () -> Unit,
    onAction: (TaskActionButton) -> Unit,
    modifier: Modifier = Modifier,
    /** App chrome, not a server action: typing a document number the picker accepts as a scan. */
    manualEntryLabel: String? = null,
    onManualEntry: () -> Unit = {},
) {
    if (primary == null && secondary.isEmpty() && !showQtyField && manualEntryLabel == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.gutter, vertical = if (dimens.compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            secondary.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { action ->
                        OutlinedButton(
                            onClick = { onAction(action) },
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = dimens.actionHeight),
                        ) { ActionLabel(action.label) }
                    }
                    if (pair.size == 1 && secondary.size > 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (manualEntryLabel != null) {
                OutlinedButton(
                    onClick = onManualEntry,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimens.actionHeight),
                ) { ActionLabel(manualEntryLabel) }
            }
            // The quantity and the key that sends it share a row: one line
            // of keys less on a short screen.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showQtyField) {
                    OutlinedButton(
                        onClick = onQtyEntry,
                        enabled = enabled,
                        shape = CardShape,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = dimens.actionHeight),
                    ) {
                        Text(
                            text = qtyInput.ifEmpty { stringResource(R.string.task_enter_quantity) },
                            style = if (qtyInput.isEmpty()) MaterialTheme.typography.titleMedium else dimens.qtyStyle,
                            maxLines = 1,
                        )
                    }
                }
                if (primary != null) {
                    Button(
                        onClick = { onAction(primary) },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = dimens.actionHeight),
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            ActionLabel(primary.label)
                        }
                    }
                } else if (isSending) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Shown when an action never reached the server; retry reuses its operation id. */
@Composable
fun TaskRetryBar(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.task_retrying),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.task_retry),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** Free-text entry for an unreadable cell label or a typed document number. */
@Composable
fun TaskManualEntryDialog(
    title: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = CardShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSubmit(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        shape = CardShape,
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * Quantity entry for an `expect: qty` step. [confirmLabel] is the server's
 * primary action label, so the dialog's button says what the step's button
 * would. Digits only: `quantity` is integer pieces on the wire.
 */
@Composable
fun TaskQuantityDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember {
        mutableStateOf(TextFieldValue(text = initialValue, selection = TextRange(0, initialValue.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val canSubmit = value.text.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.all(Char::isDigit)) value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.task_enter_quantity)) },
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit(value.text) }),
                shape = CardShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            Button(onClick = { onSubmit(value.text) }, enabled = canSubmit) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        shape = CardShape,
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
