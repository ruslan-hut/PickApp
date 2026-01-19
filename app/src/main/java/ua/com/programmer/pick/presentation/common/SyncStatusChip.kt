package ua.com.programmer.pick.presentation.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.SyncStatus
import ua.com.programmer.pick.ui.theme.ChipShape

@Composable
fun SyncStatusChip(
    status: SyncStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val containerColor by animateColorAsState(
        targetValue = when (status) {
            SyncStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
            SyncStatus.SYNCING -> MaterialTheme.colorScheme.primaryContainer
            SyncStatus.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
            SyncStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = tween(durationMillis = 300),
        label = "chip_container_color"
    )

    val contentColor by animateColorAsState(
        targetValue = when (status) {
            SyncStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            SyncStatus.SYNCING -> MaterialTheme.colorScheme.onPrimaryContainer
            SyncStatus.SUCCESS -> MaterialTheme.colorScheme.onSecondaryContainer
            SyncStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        },
        animationSpec = tween(durationMillis = 300),
        label = "chip_content_color"
    )

    val icon: Int = when (status) {
        SyncStatus.IDLE -> R.drawable.outline_schedule_24
        SyncStatus.SYNCING -> R.drawable.outline_sync_24
        SyncStatus.SUCCESS -> R.drawable.outline_check_circle_24
        SyncStatus.ERROR -> R.drawable.outline_error_24
    }

    val text = when (status) {
        SyncStatus.IDLE -> stringResource(R.string.sync_idle)
        SyncStatus.SYNCING -> stringResource(R.string.sync_syncing)
        SyncStatus.SUCCESS -> stringResource(R.string.sync_success)
        SyncStatus.ERROR -> stringResource(R.string.sync_error)
    }

    // Rotation animation for syncing state
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    AssistChip(
        onClick = onClick ?: {},
        label = { Text(text = text) },
        modifier = modifier,
        enabled = onClick != null,
        leadingIcon = {
            Icon(
                painter = painterResource( icon),
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .then(
                        if (status == SyncStatus.SYNCING) {
                            Modifier.rotate(rotation)
                        } else {
                            Modifier
                        }
                    )
            )
        },
        shape = ChipShape,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
            leadingIconContentColor = contentColor
        ),
        border = null
    )
}
