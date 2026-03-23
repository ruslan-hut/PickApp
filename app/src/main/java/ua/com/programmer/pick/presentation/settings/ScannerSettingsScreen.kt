package ua.com.programmer.pick.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ua.com.programmer.pick.R
import ua.com.programmer.pick.core.scanner.ScannerSettings
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.SectionHeader
import ua.com.programmer.pick.ui.theme.ButtonShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTest: () -> Unit,
    viewModel: ScannerSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.settingsState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val snackbarText = when (uiState.snackbarMessage) {
        ScannerSettingsViewModel.SAVED -> stringResource(R.string.scanner_settings_saved)
        ScannerSettingsViewModel.COPIED -> stringResource(R.string.scanner_diag_copied)
        ScannerSettingsViewModel.NO_DATA -> stringResource(R.string.scanner_diag_no_data)
        else -> null
    }

    LaunchedEffect(snackbarText) {
        snackbarText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = stringResource(R.string.scanner_settings),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Detection Settings
            SectionHeader(
                title = stringResource(R.string.scanner_detection_settings),
                icon = R.drawable.outline_settings_24
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = uiState.keystrokeTimeout,
                    onValueChange = viewModel::updateKeystrokeTimeout,
                    label = { Text(stringResource(R.string.scanner_keystroke_timeout)) },
                    supportingText = { Text(stringResource(R.string.scanner_keystroke_timeout_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = ButtonShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.minBarcodeLength,
                    onValueChange = viewModel::updateMinBarcodeLength,
                    label = { Text(stringResource(R.string.scanner_min_barcode_length)) },
                    supportingText = { Text(stringResource(R.string.scanner_min_barcode_length_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = ButtonShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.scanner_terminator_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = uiState.terminatorKey == ScannerSettings.TERMINATOR_ENTER,
                        onClick = { viewModel.updateTerminatorKey(ScannerSettings.TERMINATOR_ENTER) }
                    )
                    Text("Enter", modifier = Modifier.padding(end = 16.dp))

                    RadioButton(
                        selected = uiState.terminatorKey == ScannerSettings.TERMINATOR_TAB,
                        onClick = { viewModel.updateTerminatorKey(ScannerSettings.TERMINATOR_TAB) }
                    )
                    Text("Tab", modifier = Modifier.padding(end = 16.dp))

                    RadioButton(
                        selected = uiState.terminatorKey == ScannerSettings.TERMINATOR_BOTH,
                        onClick = { viewModel.updateTerminatorKey(ScannerSettings.TERMINATOR_BOTH) }
                    )
                    Text(stringResource(R.string.scanner_terminator_both))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.prefixToStrip,
                    onValueChange = viewModel::updatePrefixToStrip,
                    label = { Text(stringResource(R.string.scanner_prefix_to_strip)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = ButtonShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.suffixToStrip,
                    onValueChange = viewModel::updateSuffixToStrip,
                    label = { Text(stringResource(R.string.scanner_suffix_to_strip)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = ButtonShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = viewModel::saveSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = ButtonShape
                ) {
                    Text(stringResource(R.string.save_settings))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Test Scanner
            SectionHeader(
                title = stringResource(R.string.scanner_test),
                icon = R.drawable.outline_info_24
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToTest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = ButtonShape
                ) {
                    Text(stringResource(R.string.scanner_open_test))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Diagnostics
            SectionHeader(
                title = stringResource(R.string.scanner_diagnostics),
                icon = R.drawable.outline_info_24
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.scanner_diag_recording),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (uiState.diagEnabled) {
                                stringResource(R.string.scanner_diag_status_recording, uiState.diagEventCount)
                            } else if (uiState.diagEventCount > 0) {
                                stringResource(R.string.scanner_diag_status_stopped, uiState.diagEventCount)
                            } else {
                                stringResource(R.string.scanner_diag_status_idle)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.diagEnabled,
                        onCheckedChange = viewModel::toggleDiagnostics
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = viewModel::copyDiagnosticsToClipboard,
                    modifier = Modifier.fillMaxWidth(),
                    shape = ButtonShape
                ) {
                    Text(stringResource(R.string.scanner_diag_copy))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
