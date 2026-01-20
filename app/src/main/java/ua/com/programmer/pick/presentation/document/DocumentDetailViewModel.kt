package ua.com.programmer.pick.presentation.document

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.domain.repository.DocumentRepository
import javax.inject.Inject

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository
) : ViewModel() {

    companion object {
        const val ERROR_LOADING_DOCUMENT = "ERROR_LOADING_DOCUMENT"
        private const val ERROR_SAVING = "ERROR_SAVING"
    }

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    fun load(documentId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val doc = documentRepository.getDocumentById(documentId)
                val lines = documentRepository.getLinesByDocumentId(documentId).first()
                _uiState.update { it.copy(document = doc, lines = lines, isLoading = false) }
            } catch (ex: Exception) {
                _uiState.update { it.copy(errorMessage = ERROR_LOADING_DOCUMENT, isLoading = false) }
            }
        }
    }

    fun updateLineQuantity(lineId: String, newQuantity: Double) {
        viewModelScope.launch {
            _uiState.update { current ->
                val updated = current.lines.map { if (it.id == lineId) it.copy(actualQuantity = newQuantity) else it }
                val newTotalActual = updated.sumOf { it.actualQuantity }
                val updatedDocument = current.document?.copy(totalActual = newTotalActual)
                current.copy(document = updatedDocument, lines = updated, isSaving = true)
            }

            try {
                documentRepository.updateLine(lineId, newQuantity, null)
                _uiState.update { it.copy(isSaving = false) }
            } catch (ex: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = ex.message ?: ERROR_SAVING) }
            }
        }
    }
}
