package ua.com.programmer.pick.presentation.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.OperatingMode
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.UserRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val userRepository: UserRepository,
    private val syncOrchestrator: SyncOrchestrator
) : ViewModel() {

    companion object {
        const val ERROR_LOADING_DOCUMENTS = "ERROR_LOADING_DOCUMENTS"
    }

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    init {
        observeDocuments()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDocuments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                userRepository.getCurrentUser()
                    .flatMapLatest { user ->
                        val mode = user?.operatingMode ?: OperatingMode.RECEIPT
                        documentRepository.getDocumentsByType(mode.toDocumentType())
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
            syncOrchestrator.requestFullSync()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun onDocumentClick(documentId: String, onNavigate: (String) -> Unit) {
        onNavigate(Screen.DocumentDetail.createRoute(documentId))
    }
}
