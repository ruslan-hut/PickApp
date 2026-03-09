package ua.com.programmer.pick.presentation.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.presentation.common.EmptyState
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.PickElevatedCard
import ua.com.programmer.pick.presentation.common.SectionHeader
import ua.com.programmer.pick.ui.theme.CardShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    modifier: Modifier = Modifier,
    documentId: String,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: DocumentDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var barcodeAlert by remember { mutableStateOf<BarcodeAlertType?>(null) }
    var showCompleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(documentId) {
        viewModel.load(documentId)
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is DocumentDetailUiEvent.ShowToast -> {
                    val message = context.getString(event.messageType.resId)
                    snackbarHostState.showSnackbar(message)
                }
                is DocumentDetailUiEvent.ShowBarcodeAlert -> {
                    barcodeAlert = event.alertType
                }
                is DocumentDetailUiEvent.NavigateBack -> {
                    onNavigateBack?.invoke()
                }
            }
        }
    }

    barcodeAlert?.let { alertType ->
        BarcodeAlertDialog(
            alertType = alertType,
            onDismiss = { barcodeAlert = null }
        )
    }

    if (showCompleteConfirm) {
        val incompleteCount = uiState.lines.count {
            it.plannedQuantity > 0 && it.actualQuantity < it.plannedQuantity
        }
        CompleteConfirmDialog(
            incompleteCount = incompleteCount,
            totalCount = uiState.lines.count { it.plannedQuantity > 0 },
            onConfirm = {
                showCompleteConfirm = false
                viewModel.packageDocument()
            },
            onDismiss = { showCompleteConfirm = false }
        )
    }

    // Auto-scroll to selected line when barcode is scanned
    LaunchedEffect(uiState.selectedLineId) {
        val selectedId = uiState.selectedLineId ?: return@LaunchedEffect
        val lineIndex = uiState.lines.indexOfFirst { it.id == selectedId }
        if (lineIndex >= 0) {
            // Account for header items: DocumentHeaderCard (0) + SectionHeader (1)
            val scrollIndex = lineIndex + 2
            listState.animateScrollToItem(scrollIndex)
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = uiState.document?.number ?: stringResource(R.string.documents),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            DocumentActionBar(
                documentState = uiState.document?.state,
                isProcessing = uiState.isProcessingAction,
                canTake = uiState.canTakeIntoWork,
                canPackage = uiState.canPackage,
                canComplete = uiState.canComplete,
                canRelease = uiState.canRelease,
                onTakeIntoWork = { viewModel.takeIntoWork() },
                onPackage = {
                    val incompleteCount = uiState.lines.count {
                        it.plannedQuantity > 0 && it.actualQuantity < it.plannedQuantity
                    }
                    if (incompleteCount == 0) {
                        viewModel.packageDocument()
                    } else {
                        showCompleteConfirm = true
                    }
                },
                onComplete = { viewModel.completeDocument() },
                onRelease = { viewModel.releaseFromPackaging() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                    )
                }

                uiState.errorMessage != null -> {
                    val errKey = uiState.errorMessage
                    val errText = if (errKey == DocumentDetailViewModel.ERROR_LOADING_DOCUMENT) {
                        stringResource(R.string.error_loading_document)
                    } else {
                        errKey ?: ""
                    }
                    Text(
                        text = errText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }

                uiState.document != null -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        // Document header card
                        item {
                            DocumentHeaderCard(
                                clientName = uiState.document?.clientName ?: "",
                                warehouseName = uiState.document?.warehouseName ?: "",
                                totalPlanned = uiState.document?.totalPlanned ?: 0.0,
                                totalActual = uiState.document?.totalActual ?: 0.0,
                                linesTotal = uiState.lines.size,
                                linesCompleted = uiState.lines.count {
                                    it.actualQuantity >= it.plannedQuantity
                                }
                            )
                        }

                        // Lines section header
                        item {
                            SectionHeader(
                                title = stringResource(R.string.document_lines),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        if (uiState.lines.isEmpty()) {
                            item {
                                EmptyState(
                                    message = stringResource(R.string.no_lines_description),
                                    icon = R.drawable.outline_inventory_2_24
                                )
                            }
                        } else {
                            items(
                                items = uiState.lines,
                                key = { it.id }
                            ) { line ->
                                DocumentLineRow(
                                    line = line,
                                    productImage = uiState.productImages[line.productId],
                                    onQuantityChange = { lineId, qty ->
                                        viewModel.updateLineQuantity(lineId, qty)
                                    },
                                    isSelected = uiState.selectedLineId == line.id,
                                    canEdit = uiState.canEdit
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentHeaderCard(
    clientName: String,
    warehouseName: String,
    totalPlanned: Double,
    totalActual: Double,
    linesTotal: Int,
    linesCompleted: Int,
    modifier: Modifier = Modifier
) {
    val progress = if (totalPlanned > 0) {
        (totalActual / totalPlanned).toFloat().coerceIn(0f, 1f)
    } else 0f

    val isComplete = linesCompleted >= linesTotal

    PickElevatedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = if (isComplete) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Client name
            if (clientName.isNotBlank()) {
                Text(
                    text = clientName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Warehouse name
            if (warehouseName.isNotBlank()) {
                Text(
                    text = warehouseName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CardShape),
                color = if (isComplete) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.planned, totalPlanned),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isComplete) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.actual, totalActual),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isComplete) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.lines_progress_fmt, linesCompleted, linesTotal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isComplete) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentActionBar(
    documentState: DocumentState?,
    isProcessing: Boolean,
    canTake: Boolean,
    canPackage: Boolean,
    canComplete: Boolean,
    canRelease: Boolean,
    onTakeIntoWork: () -> Unit,
    onPackage: () -> Unit,
    onComplete: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (documentState == null) return

    if (documentState != DocumentState.LOADED &&
        documentState != DocumentState.IN_PROGRESS &&
        documentState != DocumentState.PACKAGING) {
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            when (documentState) {
                DocumentState.LOADED -> {
                    if (canTake) {
                        Button(
                            onClick = onTakeIntoWork,
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.take_into_work))
                            }
                        }
                    }
                }
                DocumentState.IN_PROGRESS -> {
                    if (canPackage) {
                        Button(
                            onClick = onPackage,
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.package_document))
                            }
                        }
                    }
                }
                DocumentState.PACKAGING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (canRelease) {
                            Button(
                                onClick = onRelease,
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text(stringResource(R.string.release_document))
                            }
                        }
                        if (canComplete) {
                            Button(
                                onClick = onComplete,
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(stringResource(R.string.complete_document))
                                }
                            }
                        }
                    }
                }
                else -> { /* No action bar for other states */ }
            }
        }
    }
}

@Composable
private fun CompleteConfirmDialog(
    incompleteCount: Int,
    totalCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.complete_document_confirm_title))
        },
        text = {
            Text(stringResource(R.string.complete_document_remain_fmt, incompleteCount, totalCount))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.no))
            }
        }
    )
}

@Composable
private fun BarcodeAlertDialog(
    alertType: BarcodeAlertType,
    onDismiss: () -> Unit
) {
    val isError = alertType == BarcodeAlertType.PRODUCT_NOT_IN_DOCUMENT
    val containerColor = if (isError) Color(0xFFD32F2F) else Color(0xFFFFC107)
    val contentColor = if (isError) Color.White else Color(0xFF1A1A1A)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        text = {
            Text(
                text = stringResource(alertType.resId),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ok), color = contentColor)
            }
        }
    )
}
