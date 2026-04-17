package ua.com.programmer.pick.presentation.document

import ua.com.programmer.pick.domain.model.Box
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentBox
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.ProductImage

// DocumentDetailTab distinguishes the two content areas shown by the screen.
// PRODUCTS is the legacy list of DocumentLines. BOXES is new for the PACK stage
// (and remains visible afterwards for read-only inspection of the packed result).
enum class DocumentDetailTab { PRODUCTS, BOXES }

data class DocumentDetailUiState(
    val document: Document? = null,
    val lines: List<DocumentLine> = emptyList(),
    val productImages: Map<String, ProductImage> = emptyMap(),
    val documentBoxes: List<DocumentBox> = emptyList(),
    val activeTab: DocumentDetailTab = DocumentDetailTab.PRODUCTS,
    // While the parcel weight dialog is open we block additional scans so the
    // worker doesn't start the next parcel before entering the previous weight.
    // Null means no dialog is open.
    val pendingWeightBox: Box? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isProcessingAction: Boolean = false,
    val errorMessage: String? = null,
    val selectedLineId: String? = null,
    val currentUserId: String? = null
) {
    // Editing a document requires two things: the document is in an in-process
    // stage (COLLECTING / PACKING / DELIVERING) AND the current user is the
    // one who holds the lock. The ownership check matters for the administrator
    // role, which receives every document — including those being processed by
    // other workers. For collectors/couriers the server already filters by
    // role, so the ownership check is redundant but harmless.

    /** Editable only when the document is in-process AND owned by the current user. */
    val canEdit: Boolean
        get() {
            val doc = document ?: return false
            if (!DocumentState.isInProcess(doc.state)) return false
            val owner = doc.assignedUserId ?: return false
            val me = currentUserId ?: return false
            return owner == me
        }

    /** Takeable when the document is in a stage start state (LOADED / PACK / DELIVERY). */
    val canTakeIntoWork: Boolean
        get() = document?.state?.let { DocumentState.isStageStart(it) } ?: false

    val canComplete: Boolean
        get() = canEdit

    val canRelease: Boolean
        get() = canEdit

    /** True when the worker is actively packing (PACKING state) — box add/remove is allowed. */
    val isPackStage: Boolean
        get() = document?.state == DocumentState.PACKING

    /**
     * Line quantities are frozen once the collector finishes. During PACKING the
     * collector can still see the list but must not edit actuals — ERP and the
     * server-side Worker Data Invariant take over for line data at that point.
     */
    val canEditLines: Boolean
        get() = canEdit && document?.state == DocumentState.COLLECTING

    /** True from the moment a document enters the pack stage until it ships. */
    val showBoxesTab: Boolean
        get() = document?.state?.let {
            it == DocumentState.PACK ||
                it == DocumentState.PACKING ||
                it == DocumentState.PACKED ||
                it == DocumentState.DELIVERY ||
                it == DocumentState.DELIVERING ||
                it == DocumentState.DELIVERED ||
                it == DocumentState.SENT
        } ?: false

    val parcelCount: Int get() = documentBoxes.count { it.isParcel }
    val packageCount: Int get() = documentBoxes.count { !it.isParcel }

    /** Pack stage can be completed only when at least one parcel has been added. */
    val canCompletePack: Boolean
        get() = isPackStage && parcelCount >= 1

    /** True while a parcel weight dialog is open — scans are suspended. */
    val isAwaitingWeight: Boolean get() = pendingWeightBox != null
}
