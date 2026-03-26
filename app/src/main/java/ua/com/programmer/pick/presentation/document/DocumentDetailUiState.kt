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
     * Editing is allowed only when the document is COLLECTING and taken by the current user.
     */
    val canEdit: Boolean
        get() {
            val doc = document ?: return false
            val userId = currentUserId ?: return false
            return doc.state == DocumentState.COLLECTING &&
                   doc.assignedUserId == userId
        }

    /**
     * Check if the current user can take this document into work.
     * Also allows re-locking a COLLECTING document that was unlocked on the server
     * (e.g., user exited without finishing and the server released the lock).
     */
    val canTakeIntoWork: Boolean
        get() {
            val doc = document ?: return false
            if (doc.state == DocumentState.LOADED) return true
            // Allow re-locking if COLLECTING but not currently editable by this user
            if (doc.state == DocumentState.COLLECTING && !canEdit) return true
            return false
        }

    /**
     * Check if the current user can move this document to PACKAGING.
     * Only the user who took it into work can package it.
     */
    val canPackage: Boolean
        get() {
            val doc = document ?: return false
            val userId = currentUserId ?: return false
            return doc.state == DocumentState.COLLECTING &&
                   doc.assignedUserId == userId
        }

    /**
     * Check if the current user can complete this document.
     * Document must be in PACKAGING state and owned by current user.
     */
    val canComplete: Boolean
        get() {
            val doc = document ?: return false
            val userId = currentUserId ?: return false
            return doc.state == DocumentState.PACKAGING &&
                   doc.assignedUserId == userId
        }

    /**
     * Check if the current user can release this document from PACKAGING back to IN_PROGRESS.
     */
    val canRelease: Boolean
        get() {
            val doc = document ?: return false
            val userId = currentUserId ?: return false
            return doc.state == DocumentState.PACKAGING &&
                   doc.assignedUserId == userId
        }

    /**
     * Check if the document is taken by another user.
     */
    val isTakenByOtherUser: Boolean
        get() {
            val doc = document ?: return false
            val userId = currentUserId ?: return false
            return (doc.state == DocumentState.COLLECTING || doc.state == DocumentState.PACKAGING) &&
                   doc.assignedUserId != null &&
                   doc.assignedUserId != userId
        }
}
