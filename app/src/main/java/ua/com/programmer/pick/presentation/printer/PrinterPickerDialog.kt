package ua.com.programmer.pick.presentation.printer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import ua.com.programmer.pick.R

/**
 * Picks the terminal's label printer from the warehouse's list. [onClose]
 * gets true when a printer was picked and stored on the server.
 */
@Composable
fun PrinterPickerDialog(
    onClose: (picked: Boolean) -> Unit,
    viewModel: PrinterPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var choice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.selectedId) { if (choice == null) choice = state.selectedId }

    AlertDialog(
        onDismissRequest = { onClose(false) },
        title = { Text(stringResource(R.string.label_printer_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    state.printers.isEmpty() && state.error == null ->
                        Text(stringResource(R.string.label_printer_none_configured))
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
                        items(state.printers, key = { it.id }) { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = choice == p.id, role = Role.RadioButton) { choice = p.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = choice == p.id, onClick = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${p.address} · ${p.labelWidthMm.toInt()}×${p.labelHeightMm.toInt()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.saving && choice != null && state.printers.any { it.id == choice },
                onClick = { viewModel.select(choice) { ok -> if (ok) onClose(true) } },
            ) { Text(stringResource(R.string.label_printer_pick_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { onClose(false) }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
