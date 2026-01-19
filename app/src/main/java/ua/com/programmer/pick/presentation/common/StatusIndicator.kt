package ua.com.programmer.pick.presentation.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.ui.theme.ErrorLight
import ua.com.programmer.pick.ui.theme.SecondaryLight
import ua.com.programmer.pick.ui.theme.TertiaryLight

enum class ConnectionStatus {
    ONLINE,
    OFFLINE,
    SYNCING
}

@Composable
fun StatusIndicator(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    showLabel: Boolean = false
) {
    val status = if (isOnline) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE
    StatusDot(status = status, modifier = modifier, size = size, showLabel = showLabel)
}

@Composable
fun StatusDot(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    showLabel: Boolean = false
) {
    val targetColor = when (status) {
        ConnectionStatus.ONLINE -> SecondaryLight
        ConnectionStatus.OFFLINE -> ErrorLight
        ConnectionStatus.SYNCING -> TertiaryLight
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 300),
        label = "status_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == ConnectionStatus.SYNCING) 0.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val statusLabel = when (status) {
        ConnectionStatus.ONLINE -> stringResource(R.string.online)
        ConnectionStatus.OFFLINE -> stringResource(R.string.offline)
        ConnectionStatus.SYNCING -> stringResource(R.string.sync_syncing)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .alpha(alpha)
                .background(animatedColor, CircleShape)
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
