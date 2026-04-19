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
    // boxId → display name from the master Box catalog. Populated by the
    // ViewModel alongside documentBoxes so the packed-boxes list can render
    // human-readable names instead of barcodes.
    val boxNamesById: Map<String, String> = emptyMap(),
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
    // When the collector presses finish but some lines are still not acknowledged,
    // the ViewModel surfaces the id of the first unchecked line so the screen can
    // scroll to it, and the count for the blocking warning dialog.
    val firstUncheckedLineId: String? = null,
    val uncheckedLineCount: Int = 0
) {
    // Per CLAUDE.md "Server-Driven Architecture": the app does not make
    // authorization decisions locally. The server already filters the sync
    // payload by role and gates the lock — if a document is in-process and
    // in the local cache, the server has accepted this user as its owner.
    // Scans and saves hit the server, which rejects anything it doesn't
    // authorize.

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
