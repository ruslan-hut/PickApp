package ua.com.programmer.pick.presentation.home

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.OperatingMode
import ua.com.programmer.pick.domain.model.SyncStatus
import ua.com.programmer.pick.presentation.common.OfflineBanner
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.PickElevatedCard
import ua.com.programmer.pick.presentation.common.SectionHeader
import ua.com.programmer.pick.presentation.common.StatusIndicator
import ua.com.programmer.pick.presentation.common.SyncStatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onLogoutClick: () -> Unit,
    onDocumentsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onModeChange: (OperatingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

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

                // Operating mode selector
                item {
                    OperatingModeSelector(
                        selectedMode = uiState.currentUser?.operatingMode ?: OperatingMode.RECEIPT,
                        onModeSelected = onModeChange
                    )
                }

                // Sync status section
                item {
                    SectionHeader(
                        title = stringResource(R.string.sync_settings),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    val syncStatus = uiState.syncStates.firstOrNull()?.status ?: SyncStatus.IDLE
                    SyncStatusCard(syncStatus = syncStatus)
                }

                // Quick actions section
                item {
                    SectionHeader(
                        title = stringResource(R.string.quick_actions),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Action cards
                item {
                    QuickActionCard(
                        icon = R.drawable.baseline_description_24,
                        title = stringResource(R.string.documents),
                        description = stringResource(R.string.documents_description),
                        onClick = onDocumentsClick
                    )
                }

                item {
                    QuickActionCard(
                        icon = R.drawable.baseline_person_24,
                        title = stringResource(R.string.profile),
                        description = stringResource(R.string.profile_description),
                        onClick = onProfileClick
                    )
                }

                item {
                    QuickActionCard(
                        icon = R.drawable.baseline_settings_24,
                        title = stringResource(R.string.settings),
                        description = stringResource(R.string.settings_description),
                        onClick = onSettingsClick
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
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
private fun SyncStatusCard(
    syncStatus: SyncStatus,
    modifier: Modifier = Modifier
) {
    PickElevatedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.sync_status_label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            SyncStatusChip(status = syncStatus)
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PickElevatedCard(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OperatingModeSelector(
    selectedMode: OperatingMode,
    onModeSelected: (OperatingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    PickElevatedCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.operating_mode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                OperatingMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = OperatingMode.entries.size
                        )
                    ) {
                        Text(
                            text = when (mode) {
                                OperatingMode.RECEIPT -> stringResource(R.string.mode_receipt)
                                OperatingMode.SHIPMENT -> stringResource(R.string.mode_shipment)
                                OperatingMode.INVENTORY -> stringResource(R.string.mode_inventory)
                            }
                        )
                    }
                }
            }
        }
    }
}
