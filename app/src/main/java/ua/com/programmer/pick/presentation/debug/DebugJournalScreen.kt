package ua.com.programmer.pick.presentation.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.local.database.entity.DebugJournalEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugJournalScreen(
    onNavigateBack: () -> Unit,
    viewModel: DebugJournalViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Journal") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Flush now") }, onClick = {
                            menuExpanded = false; viewModel.flushNow()
                        })
                        DropdownMenuItem(text = { Text("Prune old") }, onClick = {
                            menuExpanded = false; viewModel.pruneNow()
                        })
                        DropdownMenuItem(text = { Text("Clear all") }, onClick = {
                            menuExpanded = false; viewModel.clearAll()
                        })
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !state.isAdmin -> Text(
                    "Admin access required",
                    modifier = Modifier.align(Alignment.Center)
                )
                state.events.isEmpty() -> Text(
                    "No debug events recorded.\nAsk support to enable journaling for this device.",
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> EventList(state.groupByDocument)
            }
        }
    }
}

@Composable
private fun EventList(grouped: Map<String?, List<DebugJournalEntity>>) {
    LazyColumn(Modifier.fillMaxSize()) {
        grouped.forEach { (docId, events) ->
            item(key = "header-${docId ?: "none"}") {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    // Guided-task rows key on "task:<id>" when the task is not
                    // bound to a document, so they group like any document.
                    Text(
                        text = when {
                            docId == null -> "(session-scoped)"
                            docId.startsWith("task:") -> "Task: ${docId.removePrefix("task:")}"
                            else -> "Document: $docId"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${events.size} events",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider()
            }
            items(events, key = { it.id }) { evt ->
                EventRow(evt)
                HorizontalDivider()
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun EventRow(e: DebugJournalEntity) {
    val color = when (e.severity) {
        DebugJournal.SEVERITY_ERROR -> Color(0xFFB00020)
        DebugJournal.SEVERITY_WARN -> Color(0xFFB26A00)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeFmt.format(Date(e.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = e.eventType,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
            e.stage?.let {
                Spacer(Modifier.width(8.dp))
                Text(text = "[$it]", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(text = e.message, style = MaterialTheme.typography.bodySmall)
        e.payloadJson?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
