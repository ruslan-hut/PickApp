package ua.com.programmer.pick.presentation.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.repository.DocumentTypeConfigProvider
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val appPreferences: AppPreferences,
    private val documentTypeConfigProvider: DocumentTypeConfigProvider
) : ViewModel() {

    companion object {
        const val ERROR_LOADING_DOCUMENTS = "ERROR_LOADING_DOCUMENTS"
    }

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    private val _selectDocumentType = Channel<Unit>(Channel.CONFLATED)

    /**
     * Fires when there is no usable selected type — none picked yet, or one the
     * worker is no longer assigned. The list only ever works within one type, so
     * the screen sends the worker back to pick it.
     */
    val selectDocumentType: Flow<Unit> = _selectDocumentType.receiveAsFlow()

    init {
        observeDocuments()
        observeSelection()
    }

    private fun observeSelection() {
        combine(appPreferences.selectedDocumentType, documentTypeConfigProvider.configs) { code, configs ->
            code to configs
        }
            .onEach { (code, configs) ->
                _uiState.update { it.copy(selectedDocumentType = code, documentTypeConfigs = configs) }
                // A guided type starts a task, never a list.
                if (code == null || configs[code]?.isGuided != false) {
                    _selectDocumentType.trySend(Unit)
                }
            }
            .launchIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDocuments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Only the selected type: a cached document of another type
                // waits until its type is picked again.
                appPreferences.selectedDocumentType
                    .flatMapLatest { code ->
                        if (code == null) flowOf(emptyList()) else documentRepository.getDocumentsByType(code)
                    }
                    .collectLatest { docs ->
                        _uiState.update { it.copy(documents = docs, isLoading = false) }
                    }
            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = ERROR_LOADING_DOCUMENTS, isLoading = false) }
            }
        }
    }

    fun onRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            val documentType = appPreferences.selectedDocumentType.first()
            AppLog.i("DocumentsViewModel", "DOC_TRACE onRefresh selectedType=$documentType docsInUi=${_uiState.value.documents.size}")
            if (documentType != null) {
                syncOrchestrator.requestDocumentListRefresh(documentType)
            }

            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun onDocumentClick(documentId: String, onNavigate: (String) -> Unit) {
        onNavigate(Screen.DocumentDetail.createRoute(documentId))
    }

    /**
     * Opens the server's receiving picker (`rv_pick_doc`) so a worker can join
     * a document already in another worker's hands. The server decides which
     * documents it offers.
     */
    fun onJoinReceiving(onNavigate: (String) -> Unit) {
        val type = _uiState.value.selectedDocumentType ?: return
        onNavigate(Screen.Task.byType(type))
    }
}
