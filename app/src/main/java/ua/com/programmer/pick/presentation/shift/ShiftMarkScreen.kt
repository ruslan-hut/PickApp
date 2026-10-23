package ua.com.programmer.pick.presentation.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.ShiftAction
import ua.com.programmer.pick.presentation.common.LoadingButton
import ua.com.programmer.pick.presentation.common.OfflineBanner
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.ui.theme.FiraMono

/** How long the accepted mark stays on screen before it closes itself. */
private const val DONE_CLOSE_DELAY_MS = 2_000L

/**
 * Shift start / end. Scanning the TV's QR code is the main path — the hardware
 * scanner is live on the whole screen, there is nothing to tap. The
 * administrator's one-time code below it is the fallback. Offline both are
 * off: a mark is the server's time or nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftMarkScreen(
    uiState: ShiftMarkUiState,
    onAdminCodeChange: (String) -> Unit,
    onSubmitAdminCode: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    LaunchedEffect(uiState.isDone) {
        if (uiState.isDone) {
            delay(DONE_CLOSE_DELAY_MS)
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            PickAppBar(
                title = stringResource(
                    if (uiState.action == ShiftAction.START) R.string.shift_start else R.string.shift_end
                ),
                onNavigateBack = onNavigateBack,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OfflineBanner(isOffline = !uiState.isOnline)
            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.isDone) DoneContent(uiState) else MarkContent(uiState, onAdminCodeChange, onSubmitAdminCode)
            }
        }
    }
}

@Composable
private fun MarkContent(
    uiState: ShiftMarkUiState,
    onAdminCodeChange: (String) -> Unit,
    onSubmitAdminCode: () -> Unit,
) {
    val enabled = uiState.isOnline && !uiState.isSubmitting
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Main path: scan the QR code on the warehouse TV.
        Icon(
            painter = painterResource(R.drawable.baseline_qr_code_scanner_24),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = if (uiState.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.shift_scan_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (uiState.isOnline) R.string.shift_scan_hint else R.string.shift_offline_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (uiState.isSubmitting) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }

        uiState.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (it) {
                    is ShiftMarkError.Refused -> it.message
                    ShiftMarkError.Unreachable -> stringResource(R.string.shift_error_unreachable)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OrDivider()

        // 2. Fallback: the administrator's one-time code.
        Text(
            text = stringResource(R.string.shift_admin_code_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        val canSubmit = enabled && uiState.adminCode.length == ShiftMarkViewModel.ADMIN_CODE_LENGTH
        OutlinedTextField(
            value = uiState.adminCode,
            onValueChange = onAdminCodeChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FiraMono,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmitAdminCode() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        LoadingButton(
            text = stringResource(R.string.shift_admin_code_submit),
            onClick = onSubmitAdminCode,
            isLoading = uiState.isSubmitting,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.shift_admin_code_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DoneContent(uiState: ShiftMarkUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.outline_check_circle_24),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = uiState.doneMessage ?: stringResource(
                if (uiState.action == ShiftAction.START) R.string.shift_started else R.string.shift_ended
            ),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.device_link_or),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
