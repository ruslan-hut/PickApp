package ua.com.programmer.pick.presentation.task

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.TaskActionButton
import ua.com.programmer.pick.domain.model.TaskExpect
import ua.com.programmer.pick.presentation.common.DestructiveConfirmDialog
import ua.com.programmer.pick.presentation.common.OfflineBanner
import ua.com.programmer.pick.presentation.common.PickAppBar

/**
 * One screen for every guided task. It renders `title / hint / lock_info /
 * rows / actions / expect` and sends one action back — no per-flow code, no
 * branch on the step id.
 *
 * Back leaves the screen; the task stays open on the server and reappears
 * under *Unfinished tasks* on Home. Only the server's `cancel` action closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToDocuments: (String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var confirmAction by remember { mutableStateOf<TaskActionButton?>(null) }
    var manualEntryFor by remember { mutableStateOf<TaskExpect?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TaskUiEvent.ShowToast -> Toast.makeText(
                    context,
                    event.serverText ?: context.getString(event.messageType.resId),
                    Toast.LENGTH_LONG,
                ).show()

                is TaskUiEvent.NavigateHome -> onNavigateHome()
                is TaskUiEvent.NavigateToDocuments -> onNavigateToDocuments(event.documentId)
                is TaskUiEvent.VibrateError -> view.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS,
                )
            }
        }
    }

    // D4: the task screen is the heartbeat. A guided document is never in the
    // orchestrator's heldStageLocks, so nothing else extends the server-side
    // cell / line locks while the worker is on this screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.heartbeat()
                delay(TaskViewModel.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    // An info message is an acknowledgement, not a warning: let it fade.
    LaunchedEffect(uiState.message) {
        if (uiState.message?.isInfo == true) {
            delay(INFO_MESSAGE_TIMEOUT_MS)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            PickAppBar(
                title = uiState.step?.title ?: stringResource(R.string.task_title),
                onNavigateBack = {
                    viewModel.onLeaveScreen()
                    onNavigateBack()
                },
            )
        },
        bottomBar = {
            if (uiState.actions.isNotEmpty()) {
                TaskActionsBar(
                    actions = uiState.actions,
                    // The final screen's single `done` needs no connection.
                    enabled = uiState.canAct || uiState.isFinished,
                    isSending = uiState.isSending,
                    onAction = { action ->
                        if (action.needsConfirmation()) {
                            confirmAction = action
                        } else {
                            viewModel.onAction(action.code)
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            OfflineBanner(isOffline = !uiState.isOnline)
            uiState.message?.let { TaskMessageBanner(message = it) }
            if (uiState.retryOperationId != null) {
                TaskRetryBar(onRetry = viewModel::retryPending)
            }

            if (uiState.isLoading && uiState.step == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(48.dp))
                    CircularProgressIndicator()
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                uiState.step?.hint?.let { hint ->
                    item {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                uiState.step?.lockInfo?.let { item { TaskLockInfo(text = it) } }

                items(uiState.step?.rows.orEmpty()) { row -> TaskRowItem(row = row) }
            }

            if (!uiState.isFinished) {
                TaskInputPanel(
                    expect = uiState.expect,
                    qtyInput = uiState.qtyInput,
                    enabled = uiState.canAct,
                    canSubmitQty = uiState.canAct && uiState.qtyValue != null,
                    manualEntryLabel = uiState.manualEntryLabel(),
                    onQtyChange = viewModel::onQtyInputChange,
                    onQtySubmit = viewModel::confirmQuantity,
                    onManualEntry = { manualEntryFor = uiState.expect },
                )
            }
        }
    }

    confirmAction?.let { action ->
        DestructiveConfirmDialog(
            title = action.label,
            message = stringResource(
                if (action.code == TaskViewModel.ACTION_CANCEL) R.string.task_cancel_message
                else R.string.task_no_stock_confirm,
            ),
            onConfirm = {
                confirmAction = null
                viewModel.onAction(action.code)
            },
            onDismiss = { confirmAction = null },
        )
    }

    manualEntryFor?.let { expect ->
        TaskManualEntryDialog(
            title = stringResource(
                if (expect == TaskExpect.CELL) R.string.task_enter_cell else R.string.task_enter_document,
            ),
            onSubmit = { value ->
                manualEntryFor = null
                if (expect == TaskExpect.CELL) viewModel.submitManualCell(value)
                else viewModel.submitManualValue(value)
            },
            onDismiss = { manualEntryFor = null },
        )
    }
}

private const val INFO_MESSAGE_TIMEOUT_MS = 4_000L

/**
 * A cell may be typed only when the server offers `manual_cell` — and then with
 * the server's own label. A document number may always be typed, because the
 * receiving picker accepts it as a scan; that button is app chrome.
 */
@Composable
private fun TaskUiState.manualEntryLabel(): String? = when (expect) {
    TaskExpect.CELL -> actions.firstOrNull { it.code == TaskViewModel.ACTION_MANUAL_CELL }?.label
    TaskExpect.DOCUMENT -> stringResource(R.string.task_manual_entry)
    else -> null
}

private fun TaskActionButton.needsConfirmation(): Boolean =
    code == TaskViewModel.ACTION_CANCEL || code == TaskViewModel.ACTION_NO_STOCK
