package ua.com.programmer.pick.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.websocket.AvailableDocumentTypeDto
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncOrchestrator: SyncOrchestrator,
    private val appPreferences: AppPreferences,
    private val gson: Gson
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _navigateToLogin = MutableSharedFlow<Unit>()
    val navigateToLogin = _navigateToLogin.asSharedFlow()

    init {
        observeNetworkState()
        loadCurrentUser()
        loadAvailableDocumentTypes()
        observeAuthState()
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                _uiState.update { it.copy(isOnline = isOnline) }
            }
        }
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            userRepository.getCurrentUser().collect { user ->
                _uiState.update { it.copy(currentUser = user) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            userRepository.logout()
        }
    }

    fun syncData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                syncOrchestrator.processPendingOperations()
                syncOrchestrator.requestDeltaSync()
            } catch (_: Exception) {
                // Sync errors are tracked in SyncOrchestrator.syncState
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun loadAvailableDocumentTypes() {
        viewModelScope.launch {
            appPreferences.availableDocumentTypes.collect { json ->
                val types = if (json != null) {
                    try {
                        val listType = object : TypeToken<List<AvailableDocumentTypeDto>>() {}.type
                        val dtos: List<AvailableDocumentTypeDto> = gson.fromJson(json, listType)
                        dtos.map { AvailableDocumentType(code = it.code, description = it.description) }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                _uiState.update { it.copy(availableDocumentTypes = types) }
            }
        }
        viewModelScope.launch {
            appPreferences.selectedDocumentType.collect { code ->
                _uiState.update { it.copy(selectedDocumentTypeCode = code) }
            }
        }
    }

    /**
     * Observe WebSocket auth state. If the user was authenticated and then
     * becomes unauthenticated (session expired, server kicked), navigate
     * back to the login screen so they can re-authenticate.
     */
    private fun observeAuthState() {
        viewModelScope.launch {
            var wasAuthenticated = false
            syncOrchestrator.syncState
                .map { it.isUserAuthenticated }
                .distinctUntilChanged()
                .collect { isAuthenticated ->
                    if (wasAuthenticated && !isAuthenticated) {
                        _navigateToLogin.emit(Unit)
                    }
                    wasAuthenticated = isAuthenticated
                }
        }
    }

    fun setSelectedDocumentType(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedDocumentTypeCode = code) }
            appPreferences.setSelectedDocumentType(code)
        }
    }
}
