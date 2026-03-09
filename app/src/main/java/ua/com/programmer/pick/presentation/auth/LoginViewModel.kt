package ua.com.programmer.pick.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val networkMonitor: NetworkMonitor,
    private val appPreferences: AppPreferences
) : ViewModel() {

    companion object {
        const val ERROR_EMPTY_CREDENTIALS = "ERROR_EMPTY_CREDENTIALS"
        const val ERROR_LOGIN_FAILED = "ERROR_LOGIN_FAILED"
    }

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        observeNetworkState()
        loadDeviceId()
    }

    private fun loadDeviceId() {
        viewModelScope.launch {
            val fullId = appPreferences.deviceId.first()
            _uiState.update { it.copy(deviceId = fullId.take(8)) }
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

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
