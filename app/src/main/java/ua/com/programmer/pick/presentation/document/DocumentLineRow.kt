package ua.com.programmer.pick.presentation.document

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.ProductImage
import ua.com.programmer.pick.presentation.common.PickOutlinedCard
import ua.com.programmer.pick.ui.theme.CardShape

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DocumentLineRow(
    line: DocumentLine,
    productImage: ProductImage?,
    onQuantityChange: (String, Double) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false
) {
    val progress = if (line.plannedQuantity > 0) {
        (line.actualQuantity / line.plannedQuantity).toFloat().coerceIn(0f, 1f)
    } else 0f

    val isComplete = line.actualQuantity >= line.plannedQuantity

    PickOutlinedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        borderColor = when {
            isSelected -> MaterialTheme.colorScheme.primary
            isComplete -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        containerColor = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isComplete -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Product header with image
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Product image
                if (productImage != null) {
                    ProductImageView(
                        productImage = productImage,
                        contentDescription = line.productName,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CardShape)
                    )
                }

                // Product name
                Text(
                    text = line.productName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

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
                trackColor = if (isComplete) {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
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
                        color = if (isComplete) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
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
                    onChange = { newVal -> onQuantityChange(line.id, newVal) },
                    maxValue = if (line.plannedQuantity > 0) line.plannedQuantity else Double.MAX_VALUE
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ProductImageView(
    productImage: ProductImage,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val hasUrl = !productImage.url.isNullOrBlank()
    val hasBase64 = !productImage.base64.isNullOrBlank()

    when {
        hasUrl -> {
            // Use Glide for URL - it handles caching automatically
            GlideImage(
                model = productImage.url,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = ContentScale.Crop
            )
        }
        hasBase64 -> {
            // Decode base64 to byte array and use Glide
            val imageBytes = remember(productImage.base64) {
                try {
                    Base64.decode(productImage.base64, Base64.DEFAULT)
                } catch (e: Exception) {
                    null
                }
            }
            if (imageBytes != null) {
                GlideImage(
                    model = imageBytes,
                    contentDescription = contentDescription,
                    modifier = modifier,
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
