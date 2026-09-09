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
    val documentTypeConfigs: Map<String, AvailableDocumentType> = emptyMap(),
    // The type the home screen selected; the list refresh is narrowed to it.
    val selectedDocumentType: String? = null
) {
    /**
     * Lets a second worker join a receiving document the classic list hides
     * because another worker holds its line locks: it opens the server's
     * document picker instead of a list row.
     *
     * A guided type in the login catalog is the only client-side signal that
     * the warehouse runs the WMS module (guided-tasks plan §8, follow-up 2).
     */
    val canJoinReceiving: Boolean
        get() = selectedDocumentType == AvailableDocumentType.CODE_INCOMING_RECEIPT &&
            documentTypeConfigs.values.any { it.isGuided }
}
