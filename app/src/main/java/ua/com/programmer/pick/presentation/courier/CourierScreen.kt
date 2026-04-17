package ua.com.programmer.pick.presentation.courier

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentBox
import ua.com.programmer.pick.presentation.common.EmptyState
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.ui.theme.CardShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourierScreen(
    modifier: Modifier = Modifier,
    viewModel: CourierViewModel = hiltViewModel(),
    onNavigateBack: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CourierEvent.ShowMessage -> {
                    Toast.makeText(context, resources.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is CourierEvent.AllBoxesConfirmed -> { /* Could navigate back or refresh */ }
            }
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = if (uiState.mode == CourierMode.PICKUP) {
                    stringResource(R.string.courier_pickup)
                } else {
                    stringResource(R.string.courier_delivery)
                },
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.mode == CourierMode.PICKUP,
                    onClick = { viewModel.switchMode(CourierMode.PICKUP) },
                    label = { Text(stringResource(R.string.courier_pickup)) }
                )
                FilterChip(
                    selected = uiState.mode == CourierMode.DELIVERY,
                    onClick = { viewModel.switchMode(CourierMode.DELIVERY) },
                    label = { Text(stringResource(R.string.courier_delivery)) }
                )
            }

            // Progress indicator when a document is selected
            if (uiState.selectedDocumentId != null && uiState.totalBoxes > 0) {
                CourierProgressCard(uiState)
            }

            // Content
            if (uiState.selectedDocumentId != null) {
                // Show boxes for selected document
                Text(
                    text = stringResource(
                        if (uiState.mode == CourierMode.PICKUP) R.string.scan_to_pickup
                        else R.string.scan_to_deliver
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(
                        items = uiState.boxes,
                        key = { "${it.documentId}_${it.boxNumber}" }
                    ) { box ->
                        CourierBoxItem(box = box, mode = uiState.mode)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            } else if (uiState.documents.isEmpty()) {
                EmptyState(
                    message = stringResource(
                        if (uiState.mode == CourierMode.PICKUP) R.string.no_boxes_for_pickup
                        else R.string.no_boxes_for_delivery
                    ),
                    title = "",
                    icon = R.drawable.outline_description_24
                )
            } else {
                // Show documents list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = uiState.documents,
                        key = { it.id }
                    ) { doc ->
                        CourierDocumentItem(
                            document = doc,
                            onClick = { viewModel.selectDocument(doc.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourierProgressCard(uiState: CourierUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (uiState.mode == CourierMode.PICKUP) R.string.pickup_progress_fmt
                    else R.string.delivery_progress_fmt,
                    uiState.confirmedBoxes,
                    uiState.totalBoxes
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            val progress = if (uiState.totalBoxes > 0) {
                uiState.confirmedBoxes.toFloat() / uiState.totalBoxes
            } else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CardShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
private fun CourierDocumentItem(
    document: Document,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.outline_description_24),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.number,
                    style = MaterialTheme.typography.titleMedium
                )
                document.clientName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = document.state.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun CourierBoxItem(box: DocumentBox, mode: CourierMode) {
    val isConfirmed = when (mode) {
        CourierMode.PICKUP -> box.pickedUpAt != null
        CourierMode.DELIVERY -> box.deliveredAt != null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(
                if (isConfirmed) R.drawable.outline_check_circle_24
                else R.drawable.outline_description_24
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (isConfirmed) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = box.barcode,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.box_weight_fmt, box.weight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isConfirmed) {
            Text(
                text = "\u2713",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
