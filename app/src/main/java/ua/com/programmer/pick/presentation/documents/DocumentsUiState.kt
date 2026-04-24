package ua.com.programmer.pick.presentation.documents

import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.model.Document

data class DocumentsUiState(
    val documents: List<Document> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val query: String? = null,
    val errorMessage: String? = null,
    // Per-type capability flags keyed by document type code. Populated from
    // the user's available_document_types. Null → no config known → client
    // defaults apply via AvailableDocumentType's `*OrDefault` accessors.
    val documentTypeConfigs: Map<String, AvailableDocumentType> = emptyMap()
)
