package ua.com.programmer.pick.presentation.notice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.NewDocumentNotice

private const val MAX_LINES = 4

/**
 * Banner over the top of every screen announcing documents that just entered
 * the worker's queue — number and sales channel only. Tap hides it.
 */
@Composable
fun NewDocumentBanner(
    documents: List<NewDocumentNotice>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last content on screen while the exit animation runs.
    var content by remember { mutableStateOf(documents) }
    if (documents.isNotEmpty()) content = documents

    AnimatedVisibility(
        visible = documents.isNotEmpty(),
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (content.size == 1) {
                            stringResource(R.string.new_document_title)
                        } else {
                            stringResource(R.string.new_documents_title, content.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    content.take(MAX_LINES).forEach { doc ->
                        Text(
                            text = doc.salesChannel
                                ?.let { stringResource(R.string.new_document_line_channel, doc.number, it) }
                                ?: stringResource(R.string.new_document_line, doc.number),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (content.size > MAX_LINES) {
                        Text(
                            text = stringResource(R.string.new_documents_more, content.size - MAX_LINES),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
