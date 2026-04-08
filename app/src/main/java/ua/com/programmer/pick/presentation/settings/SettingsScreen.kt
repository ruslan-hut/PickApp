package ua.com.programmer.pick.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.common.LoadingButton
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.SectionHeader
import ua.com.programmer.pick.ui.theme.ButtonShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onScannerSettingsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.share_logs_chooser)
    val shareSubject = stringResource(R.string.share_logs_subject)

    // Handle error messages
    val errorMessage = uiState.errorMessage
    val errorText = when (errorMessage) {
        SettingsViewModel.ERROR_LOADING_SETTINGS -> stringResource(R.string.error_loading_settings)
        SettingsViewModel.ERROR_SAVING_SETTINGS -> stringResource(R.string.error_saving_settings)
        SettingsViewModel.ERROR_CLEARING_CACHE -> stringResource(R.string.error_clearing_cache)
        SettingsViewModel.ERROR_SYNC_FAILED -> stringResource(R.string.error_sync_failed)
        SettingsViewModel.ERROR_EXPORTING_LOGS -> stringResource(R.string.error_exporting_logs)
        else -> null
    }

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Handle success messages
    val successMessage = uiState.successMessage
    val successText = when (successMessage) {
        SettingsViewModel.SUCCESS_SETTINGS_SAVED -> stringResource(R.string.settings_saved)
        SettingsViewModel.SUCCESS_CACHE_CLEARED -> stringResource(R.string.cache_cleared)
        SettingsViewModel.SUCCESS_SYNC_STARTED -> stringResource(R.string.sync_started)
        else -> null
    }

    LaunchedEffect(successText) {
        successText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccess()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = stringResource(R.string.settings),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                // Server Settings Section
                SectionHeader(
                    title = stringResource(R.string.server_settings),
                    icon = R.drawable.baseline_link_24
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.serverUrl,
                        onValueChange = viewModel::updateServerUrl,
                        label = { Text(stringResource(R.string.server_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = ButtonShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LoadingButton(
                        text = stringResource(R.string.save_settings),
                        onClick = viewModel::saveSettings,
                        isLoading = uiState.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Sync Section
                SectionHeader(
                    title = stringResource(R.string.sync_settings),
                    icon = R.drawable.outline_cloud_sync_24
                )

                SettingsItem(
                    icon = R.drawable.outline_sync_24,
                    title = stringResource(R.string.force_sync),
                    subtitle = stringResource(R.string.force_sync_description),
                    onClick = viewModel::forceSync
                )

                SettingsItem(
                    icon = R.drawable.outline_delete_24,
                    title = stringResource(R.string.clear_cache),
                    subtitle = stringResource(R.string.clear_cache_description),
                    onClick = viewModel::clearCache,
                    isDestructive = true
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = R.drawable.outline_settings_24,
                    title = stringResource(R.string.scanner_settings),
                    subtitle = stringResource(R.string.scanner_settings_description),
                    onClick = onScannerSettingsClick
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Diagnostics Section
                SectionHeader(
                    title = stringResource(R.string.diagnostics),
                    icon = R.drawable.outline_info_24
                )

                SettingsItem(
                    icon = R.drawable.outline_info_24,
                    title = stringResource(R.string.share_logs),
                    subtitle = stringResource(R.string.share_logs_description),
                    onClick = {
                        viewModel.exportLogs { uri ->
                            if (uri != null) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = Intent.createChooser(sendIntent, shareChooserTitle)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(chooser)
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // App Info Section
                SectionHeader(
                    title = stringResource(R.string.app_info),
                    icon = R.drawable.outline_info_24
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_version_fmt, uiState.appVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: Int,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (isDestructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isDestructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
