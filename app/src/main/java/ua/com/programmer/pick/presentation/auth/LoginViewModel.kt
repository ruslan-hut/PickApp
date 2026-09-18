package ua.com.programmer.pick.presentation.auth

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.domain.model.DeviceNotLinkedException
import ua.com.programmer.pick.domain.model.EnrollmentQr
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val networkMonitor: NetworkMonitor,
    private val appPreferences: AppPreferences,
    private val barcodeService: BarcodeService
) : ViewModel() {

    companion object {
        private const val TAG = "LoginViewModel"
        private const val QR_LOGIN_PREFIX = "USR:"
        const val ERROR_EMPTY_CREDENTIALS = "ERROR_EMPTY_CREDENTIALS"
        const val ERROR_LOGIN_FAILED = "ERROR_LOGIN_FAILED"
    }

    // The login back-stack entry stays alive under the pairing screen and would
    // otherwise react to a QR scanned there too.
    private var isScreenActive = false

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        observeNetworkState()
        loadInitialState()
        subscribeToQRLogin()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val fullId = appPreferences.deviceId.first()
            val lastLogin = appPreferences.lastLogin.first() ?: ""
            _uiState.update {
                it.copy(
                    deviceId = fullId.take(8),
                    lastLogin = lastLogin
                )
            }
        }
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                _uiState.update { it.copy(isOfflineMode = !isOnline) }
            }
        }
    }

    fun login(login: String, password: String) {
        val trimmedLogin = login.trim()
        val trimmedPassword = password.trim()
        if (trimmedLogin.isEmpty() || trimmedPassword.isEmpty()) {
            _uiState.update { it.copy(errorMessage = ERROR_EMPTY_CREDENTIALS) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = userRepository.login(trimmedLogin, trimmedPassword)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoggedIn = true,
                            errorMessage = null
                        )
                    }
                }
                is Result.Error -> {
                    if (result.exception is DeviceNotLinkedException) {
                        _uiState.update { it.copy(isLoading = false, needsDeviceLink = true) }
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message ?: result.exception.message ?: ERROR_LOGIN_FAILED
                        )
                    }
                }
                is Result.Loading -> {
                    // Already showing loading state
                }
            }
        }
    }

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
    }

    /** The pairing screen was opened; reset the one-shot triggers. */
    fun onDeviceLinkOpened() {
        _uiState.update { it.copy(needsDeviceLink = false, enrollQr = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun subscribeToQRLogin() {
        barcodeService.scannedBarcodes
            .onEach { scanned ->
                if (scanned.rawValue.trim().startsWith(EnrollmentQr.PREFIX)) {
                    if (isScreenActive) _uiState.update { it.copy(enrollQr = scanned.rawValue.trim()) }
                    return@onEach
                }
                val credentials = parseLoginQR(scanned.rawValue) ?: return@onEach
                login(credentials.first, credentials.second)
            }
            .launchIn(viewModelScope)
    }

    private fun parseLoginQR(rawValue: String): Pair<String, String>? {
        if (!rawValue.startsWith(QR_LOGIN_PREFIX)) return null
        return try {
            val encoded = rawValue.removePrefix(QR_LOGIN_PREFIX)
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            val colonIndex = decoded.indexOf(':')
            if (colonIndex <= 0) return null
            val login = decoded.substring(0, colonIndex)
            val password = decoded.substring(colonIndex + 1)
            if (password.isEmpty()) return null
            Pair(login, password)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to parse QR login: ${e.message}")
            null
        }
    }
}
