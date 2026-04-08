package ua.com.programmer.pick.presentation.settings

import android.content.Context
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.BuildConfig
import ua.com.programmer.pick.core.util.FileLogger
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val syncStateDao: SyncStateDao,
    private val syncOrchestrator: SyncOrchestrator
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
                syncStateDao.deleteAll()
                syncOrchestrator.requestFullSync()
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
                syncStateDao.deleteAll()
                syncOrchestrator.requestFullSync()
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

    /**
     * Export the current log bundle and emit a content URI that the UI can
     * hand to Intent.ACTION_SEND. Runs on IO to avoid blocking the main thread.
     */
    fun exportLogs(onReady: (android.net.Uri?) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val file = FileLogger.exportToShareableFile(context) ?: return@withContext null
                try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    null
                }
            }
            if (uri == null) {
                _uiState.update { it.copy(errorMessage = ERROR_EXPORTING_LOGS) }
            }
            onReady(uri)
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
        const val ERROR_EXPORTING_LOGS = "ERROR_EXPORTING_LOGS"
        const val SUCCESS_SETTINGS_SAVED = "SUCCESS_SETTINGS_SAVED"
        const val SUCCESS_CACHE_CLEARED = "SUCCESS_CACHE_CLEARED"
        const val SUCCESS_SYNC_STARTED = "SUCCESS_SYNC_STARTED"
    }
}
