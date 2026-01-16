package ua.com.programmer.pick.presentation.document

import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine

data class DocumentDetailUiState(
    val document: Document? = null,
    val lines: List<DocumentLine> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)
