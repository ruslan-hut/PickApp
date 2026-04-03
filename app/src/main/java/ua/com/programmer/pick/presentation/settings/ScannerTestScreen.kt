package ua.com.programmer.pick.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.SectionHeader
import ua.com.programmer.pick.ui.theme.ButtonShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScannerSettingsViewModel = hiltViewModel()
) {
    val testState by viewModel.testState.collectAsState()
    val dwStatus by viewModel.dataWedgeStatus.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    // Start/stop test mode with screen lifecycle
    DisposableEffect(Unit) {
        viewModel.startTestMode()
        onDispose {
            viewModel.stopTestMode()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = stringResource(R.string.scanner_test),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Settings summary
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = testState.settingsSummary,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Last detected barcode
            SectionHeader(title = stringResource(R.string.scanner_test_barcode))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (testState.lastBarcode.isNotEmpty()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = testState.lastBarcode.ifEmpty {
                            stringResource(R.string.scanner_test_waiting)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (testState.lastBarcode.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                        color = if (testState.lastBarcode.isNotEmpty()) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (testState.barcodeInfo.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = testState.barcodeInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Buffer
            SectionHeader(title = stringResource(R.string.scanner_test_buffer))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = testState.bufferContent.ifEmpty { " " },
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace
                    )
                    if (testState.bufferInfo.isNotEmpty()) {
                        Text(
                            text = testState.bufferInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Key event log
            SectionHeader(title = stringResource(R.string.scanner_test_log))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(300.dp)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(8.dp)
                    )
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
            ) {
                val logScrollState = rememberScrollState()

                // Auto-scroll to bottom when log changes
                androidx.compose.runtime.LaunchedEffect(testState.logLines) {
                    logScrollState.animateScrollTo(logScrollState.maxValue)
                }

                Text(
                    text = testState.logLines.ifEmpty { stringResource(R.string.scanner_test_log_empty) },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(logScrollState)
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = if (testState.logLines.isNotEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = viewModel::clearTestLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = ButtonShape
            ) {
                Text(stringResource(R.string.scanner_test_clear))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // DataWedge diagnostics
            SectionHeader(title = stringResource(R.string.dw_diag_title))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.dw_diag_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (dwStatus.activeProfile != null || dwStatus.errorMessage != null) {
                        DataWedgeStatusCard(dwStatus)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = viewModel::queryDataWedge,
                            enabled = !dwStatus.isQuerying,
                            modifier = Modifier.weight(1f),
                            shape = ButtonShape
                        ) {
                            if (dwStatus.isQuerying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.dw_diag_query))
                        }

                        OutlinedButton(
                            onClick = viewModel::copyDataWedgeReport,
                            enabled = dwStatus.activeProfile != null,
                            modifier = Modifier.weight(1f),
                            shape = ButtonShape
                        ) {
                            Text(stringResource(R.string.dw_diag_copy))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DataWedgeStatusCard(status: ua.com.programmer.pick.core.scanner.DataWedgeStatus) {
    // Error-only state (e.g. non-Zebra device)
    if (status.activeProfile == null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = status.errorMessage ?: stringResource(R.string.dw_diag_no_response),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val lines = buildList {
        add("Profile" to (status.activeProfile))
        add("Profile enabled" to (status.profileEnabled?.toString() ?: "—"))
        add("Scanner input" to (status.scannerEnabled?.toString() ?: "—"))
        add("Intent output" to (status.intentOutputEnabled?.toString() ?: "—"))
        add("Intent action" to (status.intentAction ?: "—"))
        add("Intent delivery" to (status.intentDelivery ?: "—"))
        add("Keystroke output" to (status.keystrokeOutputEnabled?.toString() ?: "—"))
        if (status.datawedgeVersion != null) {
            add("DataWedge version" to status.datawedgeVersion)
        }
    }

    val hasIssue = status.intentOutputEnabled != true ||
            status.intentAction != "ua.com.programmer.pick.SCAN" ||
            status.intentDelivery?.lowercase()?.contains("broadcast") != true

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (hasIssue) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            lines.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasIssue) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (hasIssue) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                }
            }

            if (hasIssue) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        status.intentOutputEnabled != true ->
                            stringResource(R.string.dw_diag_hint_enable_intent)
                        status.intentAction != "ua.com.programmer.pick.SCAN" ->
                            stringResource(R.string.dw_diag_hint_wrong_action)
                        else ->
                            stringResource(R.string.dw_diag_hint_broadcast)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
