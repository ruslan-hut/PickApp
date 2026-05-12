package ua.com.programmer.pick.presentation.document

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.Box as DomainBox
import ua.com.programmer.pick.domain.model.DocumentBox
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
    val resources = LocalResources.current
    var barcodeAlert by remember { mutableStateOf<BarcodeAlertType?>(null) }
    // Dialog: ask user whether to save before leaving
    var showSaveOnBackDialog by remember { mutableStateOf(false) }
    // Dialog: confirm pack completion with parcel/package counts
    var showPackCompleteConfirm by remember { mutableStateOf(false) }
    // Dialog: confirm pause — save progress and exit without completing the stage
    var showPauseConfirm by remember { mutableStateOf(false) }
    // Dialog: orchestrator gave up trying to recover the worker's stage lock
    // (M5″ exhaustion). Holds the dropped counts captured at the moment the
    // event fired; the dialog text formats from these.
    var lockLost by remember { mutableStateOf<DocumentDetailUiEvent.LockLost?>(null) }

    LaunchedEffect(documentId) {
        viewModel.load(documentId)
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is DocumentDetailUiEvent.ShowToast -> {
                    val message = resources.getString(event.messageType.resId)
                    snackbarHostState.showSnackbar(message)
                }
                is DocumentDetailUiEvent.ShowBarcodeAlert -> {
                    barcodeAlert = event.alertType
                }
                is DocumentDetailUiEvent.NavigateBack -> {
                    onNavigateBack?.invoke()
                }
                is DocumentDetailUiEvent.LockLost -> {
                    lockLost = event
                }
            }
        }
    }

    // Intercept back navigation when user owns an in-progress document
    BackHandler(enabled = uiState.canEdit) {
        showSaveOnBackDialog = true
    }

    barcodeAlert?.let { alertType ->
        BarcodeAlertDialog(
            alertType = alertType,
            onDismiss = { barcodeAlert = null }
        )
    }

    // "Save document?" dialog shown when user tries to go back. The VM is the
    // single gate on whether the stage can complete — if any line is still
    // unacknowledged, it refuses and surfaces the blocking warning instead.
    if (showSaveOnBackDialog) {
        SaveOnBackDialog(
            onSave = {
                showSaveOnBackDialog = false
                viewModel.saveAndComplete()
            },
            onDiscard = {
                showSaveOnBackDialog = false
                viewModel.tryReleaseDocument()
            },
            onDismiss = { showSaveOnBackDialog = false }
        )
    }

    // Parcel weight dialog — shown while a parcel scan is waiting for its weight.
    // Blocks further scans until the worker either confirms or cancels.
    uiState.pendingWeightBox?.let { pending ->
        ParcelWeightDialog(
            box = pending,
            onConfirm = { weight -> viewModel.confirmParcelWeight(weight) },
            onDismiss = { viewModel.cancelParcelWeight() }
        )
    }

    // Pack-stage completion dialog — confirms parcel/package counts and lets
    // the worker back out without sending STAGE_COMPLETE.
    if (showPackCompleteConfirm) {
        PackCompleteDialog(
            parcelCount = uiState.parcelCount,
            packageCount = uiState.packageCount,
            canComplete = uiState.canCompletePack,
            onConfirm = {
                showPackCompleteConfirm = false
                viewModel.completePackStage()
            },
            onDismiss = { showPackCompleteConfirm = false }
        )
    }

    // "Pause work?" confirmation — explicit exit with progress preserved
    if (showPauseConfirm) {
        PauseConfirmDialog(
            onConfirm = {
                showPauseConfirm = false
                viewModel.pauseDocument()
            },
            onDismiss = { showPauseConfirm = false }
        )
    }

    // M5″ recovery exhausted: server repudiated this device's lock and the
    // worker's dirty edits were dropped. Show the size of what was lost so
    // the worker can flag it to the supervisor, then route back to the list.
    // Dismissing or tapping OK both navigate back — the screen is no longer
    // valid for the doc that's been reassigned away.
    lockLost?.let { event ->
        val droppedFormatted = if (event.droppedActualSum == event.droppedActualSum.toLong().toDouble()) {
            event.droppedActualSum.toLong().toString()
        } else {
            String.format("%.1f", event.droppedActualSum)
        }
        val message = if (event.droppedLineCount > 0) {
            resources.getString(
                R.string.lock_lost_dialog_message_fmt,
                event.droppedLineCount,
                droppedFormatted,
            )
        } else {
            resources.getString(R.string.lock_lost_dialog_message_zero)
        }
        AlertDialog(
            onDismissRequest = {
                lockLost = null
                onNavigateBack?.invoke()
            },
            title = { Text(text = resources.getString(R.string.lock_lost_dialog_title)) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = {
                    lockLost = null
                    onNavigateBack?.invoke()
                }) {
                    Text(text = resources.getString(R.string.lock_lost_dialog_ok))
                }
            }
        )
    }

    // Blocking "unacknowledged lines" dialog — surfaced by the VM when the
    // collector tries to finish with any line still marked not-done. Scrolls
    // the list to the first offender so the fix is one tap away.
    if (uiState.firstUncheckedLineId != null) {
        val firstId = uiState.firstUncheckedLineId
        LaunchedEffect(firstId) {
            val idx = uiState.lines.indexOfFirst { it.id == firstId }
            if (idx >= 0) {
                listState.animateScrollToItem(idx + 2)
            }
        }
        UncheckedLinesDialog(
            count = uiState.uncheckedLineCount,
            onDismiss = { viewModel.dismissUncheckedWarning() }
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

    // Show pinned progress bar when header card scrolls out of view
    val isHeaderScrolledAway by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = uiState.document?.number ?: stringResource(R.string.documents),
                onNavigateBack = if (uiState.canEdit) {
                    // Show dialog instead of navigating directly
                    { showSaveOnBackDialog = true }
                } else {
                    onNavigateBack
                },
                actions = {
                    if (uiState.canEdit) {
                        if (uiState.isProcessingAction) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { showPauseConfirm = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pause_24),
                                    contentDescription = stringResource(R.string.pause_document_cd),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (uiState.isPackStage) {
                                        // Pack stage has its own confirm dialog showing parcel counts.
                                        showPackCompleteConfirm = true
                                    } else {
                                        // VM refuses and surfaces the blocking warning when any
                                        // line is still unacknowledged; otherwise it proceeds.
                                        viewModel.saveAndComplete()
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_save_24),
                                    contentDescription = stringResource(R.string.save_complete_cd),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            // Only show "Take into Work" button for LOADED documents
            if (uiState.canTakeIntoWork) {
                TakeIntoWorkBar(
                    isProcessing = uiState.isProcessingAction,
                    onTakeIntoWork = { viewModel.takeIntoWork() }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Pinned progress bar — appears when header card scrolls out of view
            if (uiState.document != null) {
                AnimatedVisibility(
                    visible = isHeaderScrolledAway,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    PinnedProgressBar(
                        totalPlanned = uiState.document?.totalPlanned ?: 0.0,
                        totalActual = uiState.document?.totalActual ?: 0.0,
                        linesTotal = uiState.lines.size,
                        linesCompleted = uiState.lines.count { it.isCompleted },
                        requiresPlan = uiState.requiresPlan,
                        isOverCollected = uiState.hasOvercollectedLine
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Tab bar — only when the document has reached the pack stage.
                            // Before PACK there are no boxes to show; after PACK the tab
                            // stays for read-only inspection through deliver.
                            if (uiState.showBoxesTab) {
                                val tabs = listOf(
                                    DocumentDetailTab.PRODUCTS to stringResource(R.string.tab_products),
                                    DocumentDetailTab.BOXES to stringResource(
                                        R.string.tab_boxes
                                    ) + " (" + uiState.documentBoxes.size + ")"
                                )
                                TabRow(
                                    selectedTabIndex = tabs.indexOfFirst { it.first == uiState.activeTab }
                                        .coerceAtLeast(0)
                                ) {
                                    tabs.forEach { (tab, label) ->
                                        Tab(
                                            selected = uiState.activeTab == tab,
                                            onClick = { viewModel.setActiveTab(tab) },
                                            text = { Text(label) }
                                        )
                                    }
                                }
                            }

                            when {
                                uiState.showBoxesTab && uiState.activeTab == DocumentDetailTab.BOXES -> {
                                    BoxesTab(
                                        documentBoxes = uiState.documentBoxes,
                                        boxNamesById = uiState.boxNamesById,
                                        canRemove = uiState.isPackStage,
                                        onRemove = { boxNumber -> viewModel.removeBox(boxNumber) }
                                    )
                                }

                                else -> {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(vertical = 8.dp)
                                    ) {
                                        item {
                                            DocumentHeaderCard(
                                                clientName = uiState.document?.clientName ?: "",
                                                warehouseName = uiState.document?.warehouseName ?: "",
                                                totalPlanned = uiState.document?.totalPlanned ?: 0.0,
                                                totalActual = uiState.document?.totalActual ?: 0.0,
                                                linesTotal = uiState.lines.size,
                                                linesCompleted = uiState.lines.count { it.isCompleted },
                                                requiresPlan = uiState.requiresPlan,
                                                isOverCollected = uiState.hasOvercollectedLine
                                            )
                                        }

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
                                                    onToggleCompleted = { lineId, completed ->
                                                        viewModel.setLineCompleted(lineId, completed)
                                                    },
                                                    isSelected = uiState.selectedLineId == line.id,
                                                    // Line editing is allowed only during COLLECTING — during PACKING the
                                                    // product list is strictly read-only per the server-owned invariant.
                                                    canEdit = uiState.canEditLines,
                                                    allowsOverPlan = uiState.allowsOverPlan,
                                                    requiresPlan = uiState.requiresPlan
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
            }
        }
    }
}

@Composable
private fun PinnedProgressBar(
    totalPlanned: Double,
    totalActual: Double,
    linesTotal: Int,
    linesCompleted: Int,
    requiresPlan: Boolean,
    isOverCollected: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = if (requiresPlan && totalPlanned > 0) {
        (totalActual / totalPlanned).toFloat().coerceIn(0f, 1f)
    } else 0f

    val isComplete = linesCompleted >= linesTotal

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.actual, totalActual),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOverCollected) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.lines_progress_fmt, linesCompleted, linesTotal),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (requiresPlan) {
                Spacer(modifier = Modifier.height(4.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CardShape),
                    color = when {
                        isOverCollected -> MaterialTheme.colorScheme.error
                        isComplete -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
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
    requiresPlan: Boolean,
    isOverCollected: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = if (requiresPlan && totalPlanned > 0) {
        (totalActual / totalPlanned).toFloat().coerceIn(0f, 1f)
    } else 0f

    val isComplete = linesCompleted >= linesTotal

    PickElevatedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = when {
            isOverCollected -> MaterialTheme.colorScheme.errorContainer
            isComplete -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
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
                    color = when {
                        isOverCollected -> MaterialTheme.colorScheme.onErrorContainer
                        isComplete -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Warehouse name
            if (warehouseName.isNotBlank()) {
                Text(
                    text = warehouseName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isOverCollected -> MaterialTheme.colorScheme.onErrorContainer
                        isComplete -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Progress bar
            if (requiresPlan) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CardShape),
                    color = when {
                        isOverCollected -> MaterialTheme.colorScheme.error
                        isComplete -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surface
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    if (requiresPlan) {
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
                    }
                    Text(
                        text = stringResource(R.string.actual, totalActual),
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            isOverCollected -> MaterialTheme.colorScheme.error
                            isComplete -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
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
private fun TakeIntoWorkBar(
    isProcessing: Boolean,
    onTakeIntoWork: () -> Unit,
    modifier: Modifier = Modifier
) {
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
}

@Composable
private fun SaveOnBackDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.save_document_dialog_title))
        },
        text = {
            Text(stringResource(R.string.save_document_dialog_message))
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.no))
            }
        }
    )
}

@Composable
private fun PauseConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.pause_document_dialog_title))
        },
        text = {
            Text(stringResource(R.string.pause_document_dialog_message))
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
private fun UncheckedLinesDialog(
    count: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.unchecked_lines_dialog_title))
        },
        text = {
            Text(stringResource(R.string.unchecked_lines_dialog_message_fmt, count))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

// ============================================
// Pack-stage: Boxes tab + parcel weight dialog + pack completion dialog
// ============================================

/**
 * Boxes tab content — a flat list of DocumentBox rows sorted parcels-first
 * (the underlying DAO query applies ORDER BY is_parcel DESC). Parcel rows
 * are visually accented to signal their status as delivery places. In PACKING
 * state swipe-to-delete calls BOX_REMOVE; otherwise rows are read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxesTab(
    documentBoxes: List<DocumentBox>,
    boxNamesById: Map<String, String>,
    canRemove: Boolean,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val parcelCount = documentBoxes.count { it.isParcel }
    val packageCount = documentBoxes.size - parcelCount

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Text(
                text = stringResource(R.string.pack_summary_fmt, parcelCount, packageCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (documentBoxes.isEmpty()) {
            EmptyState(
                message = stringResource(R.string.scan_box_barcode),
                icon = R.drawable.outline_inventory_2_24
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(items = documentBoxes, key = { "${it.documentId}_${it.boxNumber}" }) { box ->
                val name = boxNamesById[box.boxId]
                if (canRemove) {
                    // Swipe triggers the remove request but never confirms the
                    // dismissal locally — the card snaps back, and the row only
                    // disappears once the server-confirmed delete clears it from
                    // the DAO. The `submitted` guard is required because
                    // confirmValueChange is re-evaluated throughout the drag
                    // while we keep rejecting the target; without it a single
                    // swipe emits dozens of BOX_REMOVE messages.
                    var submitted by remember { mutableStateOf(false) }
                    LaunchedEffect(submitted) {
                        if (submitted) {
                            delay(1500L)
                            submitted = false
                        }
                    }
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled && !submitted) {
                                submitted = true
                                onRemove(box.boxNumber)
                            }
                            false
                        }
                    )
                    // The red "delete slot" surface is always rendered as
                    // background — SwipeToDismissBox only exposes it while the
                    // card is displaced, so it appears the moment the user
                    // starts dragging. targetValue cannot be used as a drag
                    // signal here because confirmValueChange=false keeps it
                    // pinned to Settled throughout the gesture.
                    // The row fades while the BOX_REMOVE is in flight so the
                    // pending state is visible after release.
                    val rowAlpha by animateFloatAsState(
                        targetValue = if (submitted) 0.4f else 1f,
                        label = "boxRowAlpha"
                    )
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = CardShape
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 20.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.outline_delete_24),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Icon(
                                            painter = painterResource(R.drawable.outline_delete_24),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        ) {
                            Box(modifier = Modifier.alpha(rowAlpha)) {
                                DocumentBoxRow(box = box, name = name)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        DocumentBoxRow(box = box, name = name)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DocumentBoxRow(box: DocumentBox, name: String?, modifier: Modifier = Modifier) {
    val accentColor = if (box.isParcel) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    PickElevatedCard(
        modifier = modifier,
        containerColor = if (box.isParcel) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (box.isParcel) 2.dp else 0.dp,
                    color = accentColor,
                    shape = CardShape
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (box.isParcel) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    ) {
                        Text(
                            text = stringResource(
                                if (box.isParcel) R.string.parcel_badge else R.string.package_badge
                            ).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (box.isParcel) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = name ?: box.barcode,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = box.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (box.isParcel) {
                Text(
                    text = stringResource(R.string.box_weight_fmt, box.weight),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

/**
 * Parcel weight entry dialog. Modal — the screen blocks new scans while this
 * is open so the next parcel barcode cannot race ahead of the current one.
 * Grams only (integer), matches the server-side unit.
 */
@Composable
private fun ParcelWeightDialog(
    box: DomainBox,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val weight = input.toIntOrNull() ?: 0
    val canSubmit = weight > 0

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.parcel_weight_title)) },
        text = {
            Column {
                Text(
                    text = box.barcode,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { new -> input = new.filter { it.isDigit() } },
                    label = { Text(stringResource(R.string.parcel_weight_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit) onConfirm(weight) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canSubmit) onConfirm(weight) }, enabled = canSubmit) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.no))
            }
        }
    )
}

/**
 * Pack-stage completion confirmation. Surfaces the parcel/package counts so
 * the worker sees what they're about to ship. The confirm button is disabled
 * when no parcel is present — the server rejects that case anyway (the
 * STAGE_COMPLETE guard requires ≥1 parcel), but gating locally saves a round
 * trip and avoids a confusing error toast.
 */
@Composable
private fun PackCompleteDialog(
    parcelCount: Int,
    packageCount: Int,
    canComplete: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.complete_pack_title)) },
        text = {
            Column {
                Text(stringResource(R.string.complete_pack_message, parcelCount, packageCount))
                if (!canComplete) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pack_requires_parcel),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canComplete) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        textContentColor = MaterialTheme.colorScheme.onErrorContainer,
        text = {
            Text(
                text = stringResource(alertType.resId),
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.ok),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    )
}
