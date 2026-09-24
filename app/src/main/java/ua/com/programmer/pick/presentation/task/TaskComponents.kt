package ua.com.programmer.pick.presentation.task

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.domain.model.TaskMessage
import ua.com.programmer.pick.domain.model.TaskRow
import ua.com.programmer.pick.presentation.common.PickCard
import ua.com.programmer.pick.ui.theme.CardShape

/** Minimum height for every key on this screen — it is operated with gloves on. */
private val ACTION_HEIGHT = 56.dp

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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = content,
        )
    }
}

/** One context row of the step: text, optional planned / actual, optional accent. */
@Composable
fun TaskRowItem(
    row: TaskRow,
    modifier: Modifier = Modifier,
) {
    PickCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        containerColor = if (row.highlight) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            val quantities = formatQuantities(row)
            if (quantities != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = quantities,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.highlight) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun formatQuantities(row: TaskRow): String? = when {
    row.planned != null && row.actual != null -> "${row.actual}/${row.planned}"
    row.planned != null -> row.planned.toString()
    row.actual != null -> row.actual.toString()
    else -> null
}

/**
 * The input panel for the step's `expect`. PRODUCT / BATCH / NONE need no
 * input — the scanner is always live — so they only prompt.
 *
 * A quantity is never typed into this screen: `MainActivity.dispatchKeyEvent`
 * hands every printable key to the scanner's wedge buffer, and both the
 * hardware keypad and the numeric soft keyboard deliver digits as key events.
 * The panel shows the value and [onQtyEntry] opens [TaskQuantityDialog] — a
 * dialog is its own window, out of that path, exactly like the classic
 * document's quantity dialog.
 */
@Composable
fun TaskInputPanel(
    expect: TaskExpect,
    qtyInput: String,
    enabled: Boolean,
    manualEntryLabel: String?,
    onQtyEntry: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (expect) {
        TaskExpect.QTY -> Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = onQtyEntry,
                enabled = enabled,
                shape = CardShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ACTION_HEIGHT),
            ) {
                Text(
                    text = qtyInput.ifEmpty { stringResource(R.string.task_enter_quantity) },
                    style = if (qtyInput.isEmpty()) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                )
            }
        }

        TaskExpect.CELL, TaskExpect.DOCUMENT -> {
            Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                TaskScanPrompt(
                    text = stringResource(
                        if (expect == TaskExpect.CELL) R.string.task_scan_cell_prompt
                        else R.string.task_scan_document_prompt,
                    ),
                )
                if (manualEntryLabel != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onManualEntry,
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ACTION_HEIGHT),
                    ) {
                        Text(text = manualEntryLabel)
                    }
                }
            }
        }

        TaskExpect.PRODUCT, TaskExpect.BATCH ->
            TaskScanPrompt(
                text = stringResource(R.string.task_scan_prompt),
                modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

        TaskExpect.NONE -> Unit
    }
}

@Composable
private fun TaskScanPrompt(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The step's buttons, in the server's order and with its labels. `primary` is
 * filled, `danger` error-tonal, everything else outlined — including codes this
 * build has never seen, which are sent back as-is.
 */
@Composable
fun TaskActionsBar(
    actions: List<TaskActionButton>,
    enabled: Boolean,
    isSending: Boolean,
    onAction: (TaskActionButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isSending) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
            }
        }
        actions.forEach { action ->
            val buttonModifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ACTION_HEIGHT)
            when {
                action.isPrimary -> Button(
                    onClick = { onAction(action) },
                    enabled = enabled,
                    modifier = buttonModifier,
                ) { ActionLabel(action.label) }

                action.isDanger -> FilledTonalButton(
                    onClick = { onAction(action) },
                    enabled = enabled,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = buttonModifier,
                ) { ActionLabel(action.label) }

                else -> OutlinedButton(
                    onClick = { onAction(action) },
                    enabled = enabled,
                    modifier = buttonModifier,
                ) { ActionLabel(action.label) }
            }
        }
    }
}

@Composable
private fun ActionLabel(label: String) {
    Text(text = label, style = MaterialTheme.typography.titleMedium)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = CardShape,
                modifier = Modifier.fillMaxWidth(),
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

/** The muted lock line under the hint, when the server sends one. */
@Composable
fun TaskLockInfo(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
