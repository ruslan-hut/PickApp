package ua.com.programmer.pick.presentation.documents

import ua.com.programmer.pick.domain.model.Document

data class DocumentsUiState(
    val documents: List<Document> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val query: String? = null,
    val errorMessage: String? = null
)
