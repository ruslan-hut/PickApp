package ua.com.programmer.pick.presentation.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.repository.DocumentRepository
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val documentRepository: DocumentRepository
) : ViewModel() {

    companion object {
        const val ERROR_LOADING_DOCUMENTS = "ERROR_LOADING_DOCUMENTS"
    }

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    fun loadDocuments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val docs = documentRepository.getDocumentsByType(ua.com.programmer.pick.domain.model.DocumentType.OUTGOING_SHIPMENT).first()
                _uiState.update { it.copy(documents = docs, isLoading = false) }
            } catch (ex: Exception) {
                _uiState.update { it.copy(errorMessage = ERROR_LOADING_DOCUMENTS, isLoading = false) }
            }
        }
    }

    fun onDocumentClick(documentId: String, onNavigate: (String) -> Unit) {
        onNavigate(ua.com.programmer.pick.presentation.navigation.Screen.DocumentDetail.createRoute(documentId))
    }
}
