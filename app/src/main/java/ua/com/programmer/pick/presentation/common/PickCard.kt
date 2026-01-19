package ua.com.programmer.pick.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.ui.theme.CardShape

@Composable
fun PickCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )
    }
}

@Composable
fun PickElevatedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevation: Dp = 2.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        ElevatedCard(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
            colors = CardDefaults.elevatedCardColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )
    } else {
        ElevatedCard(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
            colors = CardDefaults.elevatedCardColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )
    }
}

@Composable
fun PickOutlinedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        OutlinedCard(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            border = BorderStroke(1.dp, borderColor),
            colors = CardDefaults.outlinedCardColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )
    } else {
        OutlinedCard(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            border = BorderStroke(1.dp, borderColor),
            colors = CardDefaults.outlinedCardColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )
    }
}
