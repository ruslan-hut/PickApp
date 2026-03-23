package ua.com.programmer.pick.presentation.document

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    isSelected: Boolean = false,
    canEdit: Boolean = false
) {
    var showImagePreview by remember { mutableStateOf(false) }

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
                            .clickable { showImagePreview = true }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
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
                        overflow = TextOverflow.Ellipsis
                    )
                    // Product code
                    if (line.productCode.isNotBlank()) {
                        Text(
                            text = line.productCode,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = ua.com.programmer.pick.ui.theme.FiraMono
                            ),
                            color = if (isComplete) {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                    maxValue = if (line.plannedQuantity > 0) line.plannedQuantity else Double.MAX_VALUE,
                    enabled = canEdit
                )
            }
        }
    }

    // Full-screen image preview dialog
    if (showImagePreview && productImage != null) {
        FullScreenImagePreview(
            productImage = productImage,
            productName = line.productName,
            onDismiss = { showImagePreview = false }
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun FullScreenImagePreview(
    productImage: ProductImage,
    productName: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset = Offset(
                                x = offset.x + pan.x,
                                y = offset.y + pan.y
                            )
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
        ) {
            val imageModel: Any? = when {
                !productImage.url.isNullOrBlank() -> productImage.url
                !productImage.base64.isNullOrBlank() -> {
                    try {
                        Base64.decode(productImage.base64, Base64.DEFAULT)
                    } catch (e: Exception) {
                        null
                    }
                }
                else -> null
            }

            if (imageModel != null) {
                GlideImage(
                    model = imageModel,
                    contentDescription = productName,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                )
            }

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface
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
