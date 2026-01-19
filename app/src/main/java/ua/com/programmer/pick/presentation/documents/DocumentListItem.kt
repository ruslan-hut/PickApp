package ua.com.programmer.pick.presentation.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.presentation.common.PickElevatedCard
import ua.com.programmer.pick.ui.theme.CardShape

@Composable
fun DocumentListItem(
    document: Document,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (document.totalPlanned > 0) {
        (document.totalActual / document.totalPlanned).toFloat().coerceIn(0f, 1f)
    } else 0f

    val isComplete = document.totalActual >= document.totalPlanned && document.totalPlanned > 0

    PickElevatedCard(
        onClick = { onClick(document.id) },
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Leading icon
            Icon(
                painter = painterResource(R.drawable.outline_description_24),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp),
                tint = if (isComplete) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = stringResource(
                        R.string.document_item_title_fmt,
                        document.number,
                        document.clientName ?: ""
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

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
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
}
