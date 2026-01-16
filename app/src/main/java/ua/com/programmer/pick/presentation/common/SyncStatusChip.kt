package ua.com.programmer.pick.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.domain.model.SyncStatus
import ua.com.programmer.pick.R

@Composable
fun SyncStatusChip(status: SyncStatus, modifier: Modifier = Modifier) {
    val bg = when (status) {
        SyncStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        SyncStatus.SYNCING -> MaterialTheme.colorScheme.primaryContainer
        SyncStatus.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
        SyncStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val text = when (status) {
        SyncStatus.IDLE -> stringResource(R.string.sync_idle)
        SyncStatus.SYNCING -> stringResource(R.string.sync_syncing)
        SyncStatus.SUCCESS -> stringResource(R.string.sync_success)
        SyncStatus.ERROR -> stringResource(R.string.sync_error)
    }

    Box(
        modifier = modifier
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurface)
    }
}
