package ua.com.programmer.pick.presentation.document

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DocumentLineRow(
    line: DocumentLine,
    productImage: ProductImage?,
    onQuantityChange: (String, Double) -> Unit,
    onToggleCompleted: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    canEdit: Boolean = false,
    allowsOverPlan: Boolean = false,
    requiresPlan: Boolean = true
) {
    var showImagePreview by remember { mutableStateOf(false) }

    val cardContent: @Composable () -> Unit = {
        LineCardContent(
            line = line,
            productImage = productImage,
            isSelected = isSelected,
            canEdit = canEdit,
            allowsOverPlan = allowsOverPlan,
            requiresPlan = requiresPlan,
            onQuantityChange = onQuantityChange,
            onImagePreview = { showImagePreview = true }
        )
    }

    if (canEdit) {
        // Swipe right → mark acknowledged (green); swipe left → clear mark (amber).
        // `submitted` prevents multi-fire: confirmValueChange is re-evaluated
        // throughout the drag, so without it one gesture could emit several
        // toggles. We always return false so the card snaps back — state flows
        // in through the VM, not from the swipe target.
        var submitted by remember(line.id) { mutableStateOf(false) }
        LaunchedEffect(submitted) {
            if (submitted) {
                kotlinx.coroutines.delay(600L)
                submitted = false
            }
        }
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (!submitted) {
                    when (value) {
                        SwipeToDismissBoxValue.StartToEnd -> {
                            submitted = true
                            onToggleCompleted(line.id, true)
                        }
                        SwipeToDismissBoxValue.EndToStart -> {
                            submitted = true
                            onToggleCompleted(line.id, false)
                        }
                        SwipeToDismissBoxValue.Settled -> Unit
                    }
                }
                false
            }
        )
        Box(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    SwipeBackground(direction = dismissState.dismissDirection)
                }
            ) {
                cardContent()
            }
        }
    } else {
        Box(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            cardContent()
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
private fun LineCardContent(
    line: DocumentLine,
    productImage: ProductImage?,
    isSelected: Boolean,
    canEdit: Boolean,
    allowsOverPlan: Boolean,
    requiresPlan: Boolean,
    onQuantityChange: (String, Double) -> Unit,
    onImagePreview: () -> Unit
) {
    val progress = if (requiresPlan && line.plannedQuantity > 0) {
        (line.actualQuantity / line.plannedQuantity).toFloat().coerceIn(0f, 1f)
    } else 0f

    // Green state represents explicit acknowledgement, not raw quantity progress.
    val isAcknowledged = line.isCompleted
    // Red state flags the user that they've collected more than planned. We
    // skip this on types where over-plan is legitimate (e.g. INCOMING_RECEIPT
    // over-delivery) and on types that don't carry plans (inventory counts).
    // Over-collected wins over acknowledged — shipping an over-collected line
    // is still a problem even if the worker swiped it.
    val isOverCollected = requiresPlan
        && !allowsOverPlan
        && line.plannedQuantity > 0
        && line.actualQuantity > line.plannedQuantity

    PickOutlinedCard(
        borderColor = when {
            isSelected -> MaterialTheme.colorScheme.primary
            isOverCollected -> MaterialTheme.colorScheme.error
            isAcknowledged -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        containerColor = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isOverCollected -> MaterialTheme.colorScheme.errorContainer
            isAcknowledged -> MaterialTheme.colorScheme.secondaryContainer
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
                            .clickable { onImagePreview() }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.productName,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            isOverCollected -> MaterialTheme.colorScheme.onErrorContainer
                            isAcknowledged -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (line.productCode.isNotBlank()) {
                        Text(
                            text = line.productCode,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = ua.com.programmer.pick.ui.theme.FiraMono
                            ),
                            color = when {
                                isOverCollected -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                isAcknowledged -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (requiresPlan) {
                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CardShape),
                    color = when {
                        isOverCollected -> MaterialTheme.colorScheme.error
                        isAcknowledged -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = when {
                        isOverCollected -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        isAcknowledged -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (requiresPlan) {
                    Text(
                        text = stringResource(R.string.planned, line.plannedQuantity),
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            isOverCollected -> MaterialTheme.colorScheme.onErrorContainer
                            isAcknowledged -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }

                Spacer(modifier = Modifier.width(16.dp))

                QuantityStepper(
                    value = line.actualQuantity,
                    onChange = { newVal -> onQuantityChange(line.id, newVal) },
                    maxValue = when {
                        allowsOverPlan -> Double.MAX_VALUE
                        line.plannedQuantity > 0 -> line.plannedQuantity
                        else -> Double.MAX_VALUE
                    },
                    enabled = canEdit
                )
            }
        }
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val color = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer
        SwipeToDismissBoxValue.Settled -> Color.Transparent
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = color,
        shape = CardShape
    ) {
        if (direction == SwipeToDismissBoxValue.Settled) return@Surface
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                Arrangement.Start
            } else {
                Arrangement.End
            }
        ) {
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    Icon(
                        painter = painterResource(R.drawable.outline_check_circle_24),
                        contentDescription = stringResource(R.string.line_acknowledge_action),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.line_clear_action),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }
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
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // Product name overlay
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = productName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
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
