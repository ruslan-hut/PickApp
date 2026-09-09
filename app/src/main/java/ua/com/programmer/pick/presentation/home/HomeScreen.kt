package ua.com.programmer.pick.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.model.OpenTask
import ua.com.programmer.pick.domain.model.UserRole
import ua.com.programmer.pick.presentation.common.DestructiveConfirmDialog
import ua.com.programmer.pick.presentation.common.OfflineBanner
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.PickElevatedCard
import ua.com.programmer.pick.presentation.common.SectionHeader
import ua.com.programmer.pick.presentation.common.StatusIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onLogoutClick: () -> Unit,
    onDocumentTypeClick: (AvailableDocumentType) -> Unit,
    onCourierClick: () -> Unit = {},
    onContinueTask: (OpenTask) -> Unit = {},
    onCancelTask: (OpenTask) -> Unit = {},
    taskTypeLabel: (String) -> String = { it },
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var taskToCancel by remember { mutableStateOf<OpenTask?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = stringResource(R.string.title_app),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onLogoutClick) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_logout_24),
                            contentDescription = stringResource(R.string.logout_cd)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Offline banner
            OfflineBanner(isOffline = !uiState.isOnline)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Welcome section
                item {
                    WelcomeCard(
                        userName = uiState.currentUser?.name ?: stringResource(R.string.user_default),
                        isOnline = uiState.isOnline
                    )
                }

                // Unfinished guided tasks (F-4): continue or cancel. Empty —
                // and so invisible — on a tenant without the WMS module.
                if (uiState.openTasks.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.task_unfinished_title),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(uiState.openTasks, key = { it.id }) { task ->
                        OpenTaskRow(
                            task = task,
                            typeLabel = taskTypeLabel(task.type),
                            onContinue = { onContinueTask(task) },
                            onCancel = { taskToCancel = task }
                        )
                    }
                }

                // Document type selector
                if (uiState.availableDocumentTypes.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.operating_mode),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(uiState.availableDocumentTypes) { docType ->
                        DocumentTypeButton(
                            description = docType.description,
                            isSelected = docType.code == uiState.selectedDocumentTypeCode,
                            onClick = { onDocumentTypeClick(docType) }
                        )
                    }
                }

                // Role-specific actions
                // COLLECTOR: documents are auto-assigned from the server queue
                // after login and after each completion — no manual action needed.
                // The document list shows the assigned document automatically.
                val userRole = uiState.currentUser?.role

                if (userRole == UserRole.COURIER) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.courier_pickup),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    item {
                        OutlinedCard(
                            onClick = onCourierClick,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(R.string.courier_pickup) + " / " + stringResource(R.string.courier_delivery),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    taskToCancel?.let { task ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.task_cancel_title),
            message = stringResource(R.string.task_cancel_message),
            onConfirm = {
                taskToCancel = null
                onCancelTask(task)
            },
            onDismiss = { taskToCancel = null }
        )
    }
}

/**
 * One unfinished task: the step the worker stopped on, its type and how long
 * ago it started. *Continue* reopens the task screen; *Cancel* releases the
 * server-side locks after a confirmation.
 */
@Composable
private fun OpenTaskRow(
    task: OpenTask,
    typeLabel: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = task.stepTitle ?: typeLabel,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (task.startedAt > 0) {
                    stringResource(R.string.task_started_ago_fmt, typeLabel, startedAgo(task.startedAt))
                } else {
                    typeLabel
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.task_continue))
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun startedAgo(startedAt: Long): String {
    val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.task_started_just_now)
        minutes < 60 -> stringResource(R.string.task_started_minutes_fmt, minutes)
        else -> stringResource(R.string.task_started_hours_fmt, minutes / 60)
    }
}

@Composable
private fun WelcomeCard(
    userName: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    PickElevatedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.welcome_fmt, userName),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIndicator(isOnline = isOnline, size = 10.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isOnline) stringResource(R.string.online) else stringResource(R.string.offline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentTypeButton(
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        )
    }
}
