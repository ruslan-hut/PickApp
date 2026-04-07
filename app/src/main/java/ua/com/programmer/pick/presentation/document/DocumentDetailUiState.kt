package ua.com.programmer.pick.presentation.document

import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.ProductImage

data class DocumentDetailUiState(
    val document: Document? = null,
    val lines: List<DocumentLine> = emptyList(),
    val productImages: Map<String, ProductImage> = emptyMap(),
    val currentUserId: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isProcessingAction: Boolean = false,
    val errorMessage: String? = null,
    val selectedLineId: String? = null
) {
    /**
     * Check if the current user can edit this document.
     * Editing is allowed in any in-process state (COLLECTING, PACKING) by the assigned user.
     */
    val canEdit: Boolean
        get() {
            val doc = document ?: return false
            val userId = currentUserId ?: return false
            return DocumentState.isInProcess(doc.state) &&
                   doc.assignedUserId == userId
        }

    /**
     * Check if the current user can take this document into work.
     * Allowed when document is in a stage start state (LOADED, PACK).
     */
    val canTakeIntoWork: Boolean
        get() {
            val doc = document ?: return false
            if (DocumentState.isStageStart(doc.state)) return true
            // Allow re-locking if in-process but not currently assigned to this user
            if (DocumentState.isInProcess(doc.state) && !canEdit) return true
            return false
        }

    /**
     * Check if the current user can complete the current stage.
     * Allowed in any in-process state (COLLECTING, PACKING) by the assigned user.
     */
    val canComplete: Boolean
        get() = canEdit

    /**
     * Check if the current user can release (unlock) this document.
     */
    val canRelease: Boolean
        get() = canEdit

    /**
     * Check if the document is taken by another user.
     */
    val isTakenByOtherUser: Boolean
        get() {
            val doc = document ?: return false
            val userId = currentUserId ?: return false
            return DocumentState.isInProcess(doc.state) &&
                   doc.assignedUserId != null &&
                   doc.assignedUserId != userId
        }
}
