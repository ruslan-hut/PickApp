package ua.com.programmer.pick.presentation.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.Document
import java.text.DateFormat
import java.util.Date

// Parking: a collected document set aside between Collect and Pack (e.g. for
// relabelling). Whether to offer park / resume is the server's call
// (Document.canPark / canResume); these composables only render it.

/** "Take into work" with the server-offered "Park" beside it. */
@Composable
internal fun TakeIntoWorkOrParkBar(
    isProcessing: Boolean,
    onTakeIntoWork: () -> Unit,
    onPark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onPark, enabled = !isProcessing, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.park_document))
            }
            Button(onClick = onTakeIntoWork, enabled = !isProcessing, modifier = Modifier.weight(1f)) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.take_into_work))
                }
            }
        }
    }
}

/** A parked document: why and since when, and "Resume" when it is the worker's. */
@Composable
internal fun ParkedBar(
    document: Document,
    canResume: Boolean,
    isProcessing: Boolean,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.parked_reason, document.parkingReason.orEmpty()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            document.parkingNote?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            }
            document.parkedAt?.let {
                Text(
                    text = stringResource(R.string.parked_since, formatParkedAt(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (canResume) {
                Button(
                    onClick = onResume,
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.resume_parked))
                    }
                }
            }
        }
    }
}

/** Picks a reason from the tenant catalog, plus an optional note. */
@Composable
internal fun ParkDialog(
    state: ParkDialogState,
    isProcessing: Boolean,
    onConfirm: (reasonId: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    val onlyReason = remember(state.reasons) { state.reasons.singleOrNull()?.id }
    val reasonId = selected ?: onlyReason

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.park_dialog_title)) },
        text = {
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.loadFailed -> Text(stringResource(R.string.park_reasons_failed))
                state.reasons.isEmpty() -> Text(stringResource(R.string.park_reasons_empty))
                else -> Column {
                    Text(
                        text = stringResource(R.string.park_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp).selectableGroup()) {
                        items(state.reasons, key = { it.id }) { reason ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = reason.id == reasonId,
                                        onClick = { selected = reason.id },
                                        role = Role.RadioButton,
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = reason.id == reasonId, onClick = null)
                                Text(reason.name, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(500) },
                        label = { Text(stringResource(R.string.park_note_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { reasonId?.let { onConfirm(it, note.trim()) } },
                enabled = reasonId != null && !isProcessing && !state.isLoading,
            ) {
                Text(stringResource(R.string.park_document))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun formatParkedAt(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
