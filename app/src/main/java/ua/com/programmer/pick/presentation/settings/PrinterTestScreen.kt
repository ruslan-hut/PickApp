package ua.com.programmer.pick.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.SectionHeader
import ua.com.programmer.pick.ui.theme.ButtonShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: PrinterTestViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = stringResource(R.string.printer_test),
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
            SectionHeader(title = stringResource(R.string.printer_test_connection))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        value = state.host,
                        onChange = viewModel::onHost,
                        label = stringResource(R.string.printer_test_host),
                        keyboardType = KeyboardType.Uri,
                        modifier = Modifier.weight(2f)
                    )
                    Field(
                        value = state.port,
                        onChange = viewModel::onPort,
                        label = stringResource(R.string.printer_test_port),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(state.widthMm, viewModel::onWidth, stringResource(R.string.printer_test_width), Modifier.weight(1f))
                    Field(state.heightMm, viewModel::onHeight, stringResource(R.string.printer_test_height), Modifier.weight(1f))
                    Field(state.gapMm, viewModel::onGap, stringResource(R.string.printer_test_gap), Modifier.weight(1f))
                    Field(state.density, viewModel::onDensity, stringResource(R.string.printer_test_density), Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.printer_test_black_is_zero),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.printer_test_black_is_zero_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.blackIsZero, onCheckedChange = viewModel::onBlackIsZero)
                }
            }

            SectionHeader(title = stringResource(R.string.printer_test_actions))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ActionButton(stringResource(R.string.printer_test_status), primary = true, enabled = !state.busy) {
                    viewModel.run(PrinterTestAction.STATUS)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(R.string.printer_test_text), true, !state.busy, Modifier.weight(1f)) {
                        viewModel.run(PrinterTestAction.TEXT_LABEL)
                    }
                    ActionButton(stringResource(R.string.printer_test_bitmap), true, !state.busy, Modifier.weight(1f)) {
                        viewModel.run(PrinterTestAction.BITMAP_LABEL)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(R.string.printer_test_self_test), false, !state.busy, Modifier.weight(1f)) {
                        viewModel.run(PrinterTestAction.SELF_TEST)
                    }
                    ActionButton(stringResource(R.string.printer_test_gap_detect), false, !state.busy, Modifier.weight(1f)) {
                        viewModel.run(PrinterTestAction.GAP_DETECT)
                    }
                }
            }

            SectionHeader(
                title = stringResource(R.string.printer_test_log),
                action = {
                    TextButton(onClick = viewModel::clearLog) {
                        Text(stringResource(R.string.printer_test_clear_log))
                    }
                }
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = state.log.joinToString("\n").ifEmpty { " " },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        shape = ButtonShape,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
    )
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit
) {
    if (primary) {
        Button(onClick = onClick, enabled = enabled, shape = ButtonShape, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, shape = ButtonShape, modifier = modifier) { Text(text) }
    }
}
