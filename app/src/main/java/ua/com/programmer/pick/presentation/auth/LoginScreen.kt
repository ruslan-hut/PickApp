package ua.com.programmer.pick.presentation.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.common.BrandLogo
import ua.com.programmer.pick.presentation.common.LoadingButton
import ua.com.programmer.pick.presentation.common.OfflineBanner
import ua.com.programmer.pick.ui.theme.ButtonShape

@Composable
fun LoginScreen(
    uiState: LoginUiState,
    onLoginClick: (String, String) -> Unit,
    onLoginSuccess: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            onLoginSuccess()
        }
    }

    // Compute mapped error text in composable scope
    val mappedErrorText = uiState.errorMessage?.let { errKey ->
        when (errKey) {
            LoginViewModel.ERROR_EMPTY_CREDENTIALS -> stringResource(R.string.error_empty_credentials)
            LoginViewModel.ERROR_LOGIN_FAILED -> stringResource(R.string.error_login_failed)
            else -> errKey
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // Offline banner at top
            OfflineBanner(isOffline = uiState.isOfflineMode)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.weight(0.3f))

                // Branding header
                BrandLogo()

                Spacer(modifier = Modifier.height(48.dp))

                // Login input
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it; onClearError() },
                    label = { Text(stringResource(R.string.label_login)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading,
                    shape = ButtonShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Password input with visibility toggle
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onClearError() },
                    label = { Text(stringResource(R.string.label_password)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.baseline_lock_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                painter = painterResource(if (passwordVisible) {
                                    R.drawable.outline_visibility_off_24
                                } else {
                                    R.drawable.outline_visibility_24
                                }),
                                contentDescription = stringResource(R.string.toggle_password_visibility_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (login.isNotBlank() && password.isNotBlank()) {
                                onLoginClick(login, password)
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading,
                    shape = ButtonShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Login button
                LoadingButton(
                    text = stringResource(R.string.login_button),
                    onClick = { onLoginClick(login, password) },
                    modifier = Modifier.fillMaxWidth(),
                    isLoading = uiState.isLoading,
                    enabled = login.isNotBlank() && password.isNotBlank()
                )

                if (mappedErrorText != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = mappedErrorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.weight(0.5f))

                if (uiState.deviceId.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.device_id_label_fmt, uiState.deviceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}