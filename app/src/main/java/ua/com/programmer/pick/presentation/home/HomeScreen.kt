package ua.com.programmer.pick.presentation.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.common.SyncStatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onLogoutClick: () -> Unit,
    onDocumentsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_app)) },
                actions = {
                    IconButton(onClick = onLogoutClick) {
                        Icon(
                            imageVector = Icons.Filled.Close,
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
                .padding(16.dp)
        ) {
            // Connection status
            Text(
                text = if (uiState.isOnline) stringResource(R.string.online) else stringResource(R.string.offline),
                style = MaterialTheme.typography.labelMedium,
                color = if (uiState.isOnline)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sync chip preview
            val syncStatus = uiState.syncStates.firstOrNull()?.status ?: ua.com.programmer.pick.domain.model.SyncStatus.IDLE
            SyncStatusChip(status = syncStatus)

            Spacer(modifier = Modifier.height(16.dp))

            // Welcome message
            Text(
                text = stringResource(R.string.welcome_fmt, uiState.currentUser?.name ?: stringResource(R.string.user_default)),
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Documents shortcut button
            Button(onClick = onDocumentsClick) {
                Text(stringResource(R.string.documents))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Placeholder for document cards/actions
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.dashboard_coming_soon),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
