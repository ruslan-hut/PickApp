package ua.com.programmer.pick.presentation.home

import ua.com.programmer.pick.domain.model.OpenTask
import ua.com.programmer.pick.presentation.navigation.Screen
import ua.com.programmer.pick.domain.repository.DocumentRepository
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
import ua.com.programmer.pick.data.remote.transport.AvailableDocumentTypeDto
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.repository.GuidedTaskRepository
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncOrchestrator: SyncOrchestrator,
    private val appPreferences: AppPreferences,
    private val guidedTaskRepository: GuidedTaskRepository,
    private val gson: Gson,
    private val documentRepository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _navigateToLogin = MutableSharedFlow<Unit>()
    val navigateToLogin = _navigateToLogin.asSharedFlow()

    init {
        observeNetworkState()
        loadCurrentUser()
        loadAvailableDocumentTypes()
        observeOpenTasks()
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
                        dtos.map {
                            AvailableDocumentType(
                                code = it.code,
                                description = it.description,
                                allowsOverPlan = it.allowsOverPlan,
                                allowsExtraLines = it.allowsExtraLines,
                                requiresPlan = it.requiresPlan,
                                mode = it.mode,
                                wmsFlow = it.wmsFlow
                            )
                        }
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

    private fun observeOpenTasks() {
        viewModelScope.launch {
            guidedTaskRepository.openTasks.collect { tasks ->
                _uiState.update { it.copy(openTasks = tasks) }
            }
        }
    }

    /**
     * Re-read the worker's unfinished tasks. Called when Home resumes, so a
     * task the worker walked away from — or one an admin cancelled — shows the
     * right state without a re-login. Nothing about a task is persisted (D6).
     */
    fun refreshOpenTasks() {
        val state = _uiState.value
        if (!state.isOnline) return
        // The endpoint exists only where the addressing module is on. A guided
        // type in the login catalog is the one client-side signal that it is —
        // without it, skip the call rather than collect a 403 on every resume.
        if (state.availableDocumentTypes.none { it.isGuided } && state.openTasks.isEmpty()) return
        viewModelScope.launch {
            guidedTaskRepository.refreshOpen()
        }
    }

    /**
     * Where "continue" goes: a document's guided task runs inside that
     * document's screen (which re-attaches it on open), so it opens there when
     * the document is cached here; a task without one — or a joined receiving
     * document this device does not list — stays on the task screen.
     */
    fun continueTask(task: OpenTask, navigate: (String) -> Unit) {
        viewModelScope.launch {
            val docId = task.documentId
            val cached = docId != null && runCatching { documentRepository.getDocumentById(docId) }.getOrNull() != null
            navigate(
                if (cached) Screen.DocumentDetail.createRoute(docId!!) else Screen.Task.byTaskId(task.id)
            )
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            guidedTaskRepository.cancel(taskId)
            guidedTaskRepository.refreshOpen()
        }
    }

    /** Description of a task type from the login catalog; the code is the fallback. */
    fun taskTypeLabel(code: String): String =
        _uiState.value.availableDocumentTypes.firstOrNull { it.code == code }?.description ?: code

    /**
     * Observe transport auth state. If the user was authenticated and then
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
