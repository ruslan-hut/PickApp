package ua.com.programmer.pick.presentation.document

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R

@Composable
fun QuantityStepper(
    value: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    minValue: Double = 0.0,
    maxValue: Double = Double.MAX_VALUE,
    step: Double = 1.0,
    enabled: Boolean = true,
    // Scan-only device: quantity changes only by scanning. +/- are disabled
    // and tapping the value offers a reset instead of manual entry.
    scanOnly: Boolean = false
) {
    var showDialog by remember { mutableStateOf(false) }
    var overflowEnteredValue by remember { mutableStateOf<Double?>(null) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Minus button
        FilledIconButton(
            onClick = { onChange((value - step).coerceAtLeast(minValue)) },
            modifier = Modifier.size(40.dp),
            enabled = enabled && !scanOnly && value > minValue,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.outline_remove_24),
                contentDescription = stringResource(R.string.minus)
            )
        }

        // Value display with animation - clickable to open manual entry dialog
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInVertically { -it } togetherWith slideOutVertically { it }
                } else {
                    slideInVertically { it } togetherWith slideOutVertically { -it }
                }
            },
            label = "quantity_animation"
        ) { targetValue ->
            Text(
                text = formatQuantity(targetValue),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .padding(horizontal = 8.dp)
                    .clickable(enabled = enabled) { showDialog = true }
            )
        }

        // Plus button
        FilledIconButton(
            onClick = { onChange((value + step).coerceAtMost(maxValue)) },
            modifier = Modifier.size(40.dp),
            enabled = enabled && !scanOnly && value < maxValue,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.baseline_add_24),
                contentDescription = stringResource(R.string.plus)
            )
        }
    }

    if (showDialog && scanOnly) {
        QuantityResetDialog(
            currentValue = value,
            minValue = minValue,
            onDismiss = { showDialog = false },
            onReset = {
                onChange(minValue)
                showDialog = false
            }
        )
    } else if (showDialog) {
        QuantityInputDialog(
            currentValue = value,
            minValue = minValue,
            maxValue = maxValue,
            onDismiss = { showDialog = false },
            onAccept = { accepted ->
                onChange(accepted)
                showDialog = false
            },
            onOverflow = { entered ->
                showDialog = false
                overflowEnteredValue = entered
            }
        )
    }

    overflowEnteredValue?.let { entered ->
        QuantityOverflowDialog(
            entered = entered,
            maxValue = maxValue,
            onDismiss = { overflowEnteredValue = null }
        )
    }
}

@Composable
private fun QuantityInputDialog(
    currentValue: Double,
    minValue: Double,
    maxValue: Double,
    onDismiss: () -> Unit,
    onAccept: (Double) -> Unit,
    onOverflow: (entered: Double) -> Unit
) {
    val initialText = formatQuantity(currentValue)
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(0, initialText.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val confirmAction = {
        val parsed = textFieldValue.text.toIntOrNull()
        if (parsed != null) {
            val entered = parsed.toDouble()
            if (entered > maxValue) {
                onOverflow(entered)
            } else {
                onAccept(entered.coerceAtLeast(minValue))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_quantity)) },
        text = {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    // Only allow digits
                    if (newValue.text.all { it.isDigit() }) {
                        textFieldValue = newValue
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { confirmAction() }
                ),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = confirmAction) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Scan-only replacement for [QuantityInputDialog]: shows the counted quantity
 * and lets the worker reset it to start the count over — no manual entry.
 */
@Composable
private fun QuantityResetDialog(
    currentValue: Double,
    minValue: Double,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quantity_scan_only_title)) },
        text = {
            Text(
                text = stringResource(R.string.quantity_scan_only_message, formatQuantity(currentValue)),
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            TextButton(
                onClick = onReset,
                enabled = currentValue > minValue
            ) {
                Text(
                    text = stringResource(R.string.quantity_reset),
                    color = if (currentValue > minValue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun QuantityOverflowDialog(
    entered: Double,
    maxValue: Double,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
        textContentColor = MaterialTheme.colorScheme.onErrorContainer,
        title = { Text(stringResource(R.string.quantity_overflow_title)) },
        text = {
            Text(
                text = stringResource(R.string.quantity_overflow_fmt, entered, maxValue),
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.ok),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    )
}

private fun formatQuantity(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format("%.1f", value)
    }
}
