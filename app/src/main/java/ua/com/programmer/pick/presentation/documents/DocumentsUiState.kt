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
     * The server names a receiving type by its `wms_flow`, and sends it only
     * where the warehouse runs the WMS module — the type code is the ERP's own
     * (1C: "ПриходнаяНакладная") and never interpreted here.
     */
    val canJoinReceiving: Boolean
        get() = selectedDocumentType?.let { documentTypeConfigs[it]?.isGuidedReceiving } == true
}
