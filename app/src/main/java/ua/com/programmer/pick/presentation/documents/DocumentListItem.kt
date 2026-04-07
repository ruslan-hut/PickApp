package ua.com.programmer.pick.presentation.documents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.ui.theme.CardShape
import ua.com.programmer.pick.ui.theme.ChipShape

@Composable
fun DocumentListItem(
    document: Document,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (document.totalPlanned > 0) {
        (document.totalActual / document.totalPlanned).toFloat().coerceIn(0f, 1f)
    } else 0f

    val isComplete = document.state == DocumentState.COLLECTED ||
        document.state == DocumentState.PACKED ||
        document.state == DocumentState.DELIVERING ||
        document.state == DocumentState.DELIVERED ||
        document.state == DocumentState.SENT

    OutlinedCard(
        onClick = { onClick(document.id) },
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isComplete) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isComplete) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Number + State badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = document.number,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                DocumentStateBadge(state = document.state)
            }

            // Client name
            if (!document.clientName.isNullOrBlank()) {
                Text(
                    text = document.clientName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Warehouse name
            if (!document.warehouseName.isNullOrBlank()) {
                Text(
                    text = document.warehouseName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CardShape),
                color = if (isComplete) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = if (isComplete) {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quantities
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.planned, document.totalPlanned),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = stringResource(R.string.actual, document.totalActual),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun DocumentStateBadge(
    state: DocumentState,
    modifier: Modifier = Modifier
) {
    val (labelRes, backgroundColor, contentColor) = when (state) {
        DocumentState.LOADED -> Triple(
            R.string.document_state_loaded,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        DocumentState.COLLECTING -> Triple(
            R.string.document_state_in_progress,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        DocumentState.COLLECTED -> Triple(
            R.string.document_state_completed,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        DocumentState.PACK -> Triple(
            R.string.document_state_pack,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        DocumentState.PACKING -> Triple(
            R.string.document_state_packing,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        DocumentState.PACKED -> Triple(
            R.string.document_state_packed,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        DocumentState.DELIVERY -> Triple(
            R.string.document_state_delivery,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        DocumentState.DELIVERING -> Triple(
            R.string.document_state_delivering,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        DocumentState.DELIVERED -> Triple(
            R.string.document_state_delivered,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        DocumentState.SENT -> Triple(
            R.string.document_state_sent,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.secondary
        )
        DocumentState.ERROR -> Triple(
            R.string.document_state_error,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }

    Surface(
        modifier = modifier,
        shape = ChipShape,
        color = backgroundColor
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
