package ua.com.programmer.pick.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.BuildConfig
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val serverUrl = appPreferences.serverUrl.first() ?: ""
                _uiState.update {
                    it.copy(
                        serverUrl = serverUrl,
                        appVersion = BuildConfig.VERSION_NAME,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = ERROR_LOADING_SETTINGS
                    )
                }
            }
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun saveSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                appPreferences.setServerUrl(_uiState.value.serverUrl)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        successMessage = SUCCESS_SETTINGS_SAVED
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = ERROR_SAVING_SETTINGS
                    )
                }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // TODO: Implement cache clearing via sync repository
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = SUCCESS_CACHE_CLEARED
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = ERROR_CLEARING_CACHE
                    )
                }
            }
        }
    }

    fun forceSync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // TODO: Implement force sync via sync repository
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = SUCCESS_SYNC_STARTED
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = ERROR_SYNC_FAILED
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    companion object {
        const val ERROR_LOADING_SETTINGS = "ERROR_LOADING_SETTINGS"
        const val ERROR_SAVING_SETTINGS = "ERROR_SAVING_SETTINGS"
        const val ERROR_CLEARING_CACHE = "ERROR_CLEARING_CACHE"
        const val ERROR_SYNC_FAILED = "ERROR_SYNC_FAILED"
        const val SUCCESS_SETTINGS_SAVED = "SUCCESS_SETTINGS_SAVED"
        const val SUCCESS_CACHE_CLEARED = "SUCCESS_CACHE_CLEARED"
        const val SUCCESS_SYNC_STARTED = "SUCCESS_SYNC_STARTED"
    }
}
