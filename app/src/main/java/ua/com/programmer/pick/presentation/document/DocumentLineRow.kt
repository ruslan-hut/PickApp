package ua.com.programmer.pick.presentation.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.presentation.common.PickOutlinedCard
import ua.com.programmer.pick.ui.theme.CardShape

@Composable
fun DocumentLineRow(
    line: DocumentLine,
    onQuantityChange: (String, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (line.plannedQuantity > 0) {
        (line.actualQuantity / line.plannedQuantity).toFloat().coerceIn(0f, 1f)
    } else 0f

    val isComplete = line.actualQuantity >= line.plannedQuantity && line.plannedQuantity > 0

    PickOutlinedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        borderColor = if (isComplete) {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Product name
            Text(
                text = line.productName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Progress indicator
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CardShape),
                color = if (isComplete) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Quantities row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.planned, line.plannedQuantity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.actual, line.actualQuantity),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isComplete) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Quantity stepper
                QuantityStepper(
                    value = line.actualQuantity,
                    onChange = { newVal -> onQuantityChange(line.id, newVal) }
                )
            }
        }
    }
}
