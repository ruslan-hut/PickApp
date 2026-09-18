package ua.com.programmer.pick.presentation.pairing

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.DeviceLinkStatus
import ua.com.programmer.pick.presentation.common.LoadingButton
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.ui.theme.FiraMono

/**
 * "Connect to company". Scanning the tenant's QR code is the main path — the
 * hardware scanner is live on the whole screen, there is nothing to tap. The
 * pairing code below it is the fallback for a device whose scanner is not set
 * up yet: the worker reads it out and the administrator types it in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePairingScreen(
    uiState: DevicePairingUiState,
    onNavigateBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    Scaffold(
        topBar = {
            PickAppBar(
                title = stringResource(R.string.device_link_title),
                onNavigateBack = onNavigateBack,
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.status == DeviceLinkStatus.APPROVED -> LinkedContent(uiState, onSignIn)
                uiState.status == DeviceLinkStatus.REJECTED -> RejectedContent()
                uiState.isEnrolling -> ConnectingContent()
                else -> PendingContent(uiState)
            }
        }
    }
}

@Composable
private fun PendingContent(uiState: DevicePairingUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Main path: scan the QR code shown in the admin panel.
        Icon(
            painter = painterResource(R.drawable.baseline_qr_code_scanner_24),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.device_link_scan_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.device_link_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        uiState.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = pairingErrorText(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OrDivider()

        // 2. Fallback: read the code out to the administrator.
        Text(
            text = stringResource(R.string.device_link_code_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        val code = uiState.code
        if (code != null) {
            val grouped = if (code.length == 8) "${code.take(4)} ${code.drop(4)}" else code
            Text(
                text = grouped,
                fontFamily = FiraMono,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                letterSpacing = 2.sp,
                color = if (uiState.isServerUnreachable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                // Read digit by digit, as the administrator will type it.
                modifier = Modifier.semantics { contentDescription = code.toList().joinToString(" ") },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    uiState.isServerUnreachable -> stringResource(R.string.device_link_error_unreachable)
                    uiState.secondsLeft != null ->
                        stringResource(R.string.device_link_code_expires_fmt, formatSeconds(uiState.secondsLeft))
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.isServerUnreachable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.device_link_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (uiState.isServerUnreachable) {
                        stringResource(R.string.device_link_error_unreachable)
                    } else {
                        stringResource(R.string.device_link_waiting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        if (uiState.deviceIdShort.isNotBlank()) {
            Text(
                text = stringResource(R.string.device_id_label_fmt, uiState.deviceIdShort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

@Composable
private fun ConnectingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.device_link_connecting),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun LinkedContent(uiState: DevicePairingUiState, onSignIn: () -> Unit) {
    ResultContent(
        icon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.secondary) },
        title = stringResource(R.string.device_link_success_title),
        message = stringResource(R.string.device_link_success_fmt, uiState.tenantName.orEmpty()),
    ) {
        Spacer(Modifier.height(32.dp))
        LoadingButton(
            text = stringResource(R.string.device_link_sign_in),
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RejectedContent() {
    ResultContent(
        icon = { Icon(Icons.Filled.Lock, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.error) },
        title = stringResource(R.string.device_link_rejected_title),
        message = stringResource(R.string.device_link_error_rejected),
    )
}

@Composable
private fun ResultContent(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    footer: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(20.dp))
        Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        footer()
    }
}

@Composable
private fun pairingErrorText(error: PairingError): String = when (error) {
    PairingError.OtherServer -> stringResource(R.string.device_link_error_other_server)
    PairingError.Unreachable -> stringResource(R.string.device_link_error_unreachable)
    is PairingError.Refused -> when (error.code) {
        "ENROLLMENT_INVALID" -> stringResource(R.string.device_link_error_invalid)
        "DEVICE_ASSIGNED_ELSEWHERE" -> stringResource(R.string.device_link_error_elsewhere)
        "DEVICE_REJECTED" -> stringResource(R.string.device_link_error_rejected)
        "DEVICE_LIMIT_REACHED" -> stringResource(R.string.device_link_error_limit)
        else -> stringResource(R.string.device_link_error_generic)
    }
}

private fun formatSeconds(total: Int): String = "%d:%02d".format(total / 60, total % 60)
