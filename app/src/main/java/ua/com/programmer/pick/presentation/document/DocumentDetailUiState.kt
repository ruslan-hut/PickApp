package ua.com.programmer.pick.presentation.document

import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.ProductImage

data class DocumentDetailUiState(
    val document: Document? = null,
    val lines: List<DocumentLine> = emptyList(),
    val productImages: Map<String, ProductImage> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isProcessingAction: Boolean = false,
    val errorMessage: String? = null,
    val selectedLineId: String? = null
) {
    // Per CLAUDE.md "Server-Driven Architecture": the app is a thin display
    // layer and does NOT make authorization decisions locally. The server
    // already filters the sync payload by role — a collector only ever sees
    // documents they're allowed to work on. If a document is in an
    // in-process state and the app has it in its local cache, by definition
    // the current user is authorized to edit it. Take/complete/release
    // follow the same logic: allow the action locally, let the server
    // accept or reject the resulting STAGE_* message authoritatively.

    /** Editable when the document is in an in-process state (COLLECTING / PACKING / DELIVERING). */
    val canEdit: Boolean
        get() = document?.state?.let { DocumentState.isInProcess(it) } ?: false

    /** Takeable when the document is in a stage start state (LOADED / PACK / DELIVERY). */
    val canTakeIntoWork: Boolean
        get() = document?.state?.let { DocumentState.isStageStart(it) } ?: false

    val canComplete: Boolean
        get() = canEdit

    val canRelease: Boolean
        get() = canEdit
}
