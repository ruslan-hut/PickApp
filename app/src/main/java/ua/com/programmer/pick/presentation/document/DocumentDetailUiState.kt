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
    val uncheckedLineCount: Int = 0,
    // Session-scoped stage lock claim. Always false when the screen first
    // opens — the worker must retake the document via the server (see feedback
    // memory "Document lock state is never client-persisted"). Flipped to true
    // only after a successful STAGE_LOCK_RESULT, and back to false on pause,
    // release, completion, or any server rejection that invalidates the lock.
    val hasStageLock: Boolean = false,
    // Resolved from the current document's type against the user's
    // available_document_types capability flags. `true` means actual quantity
    // may legitimately exceed plan — manual/scan caps and over-plan swipe
    // refusals are lifted. Defaults to `false` so behavior matches pre-flags.
    val allowsOverPlan: Boolean = false,
    // When true, scanning a product not listed in the doc creates a new line
    // on the fly (historical INVENTORY behavior, now ERP-configurable).
    val allowsExtraLines: Boolean = false,
    // When false, the type doesn't carry planned quantities — the UI hides
    // plan labels and progress bars. Defaults to true to match pre-flags.
    val requiresPlan: Boolean = true,
    // Toggled by tapping the pinned progress bar. When true, the products
    // list hides lines already marked as completed so the worker can focus
    // on the remaining items. Reset to false on every document load.
    val showOnlyUnchecked: Boolean = false
) {
    // Per CLAUDE.md "Server-Driven Architecture": the app does not make
    // authorization decisions locally. The server already filters the sync
    // payload by role and gates the lock — if a document is in-process and
    // in the local cache, the server has accepted this user as its owner.
    // Scans and saves hit the server, which rejects anything it doesn't
    // authorize.

    /**
     * Editable only when this device actually holds a confirmed stage lock
     * AND the server has the document in an in-process state. Requiring
     * `hasStageLock` prevents the UI from entering the working mode based on
     * stale local state alone — every edit session starts with an explicit
     * server-confirmed handshake via [canTakeIntoWork] → STAGE_LOCK.
     */
    val canEdit: Boolean
        get() = hasStageLock && (document?.state?.let { DocumentState.isInProcess(it) } ?: false)

    /**
     * The "Take into work" button is shown whenever we don't hold a confirmed
     * lock for this document. We deliberately don't gate on local state — if
     * the server still has the doc in an in-process state (e.g. a previous
     * session), re-locking is idempotent and correctly puts us back into
     * edit mode; if the server has moved on, the lock request fails and the
     * user is told why.
     */
    val canTakeIntoWork: Boolean
        get() = document != null && !hasStageLock

    val canComplete: Boolean
        get() = canEdit

    val canRelease: Boolean
        get() = canEdit

    /** True when we hold the lock and the server reports this doc as packing. */
    val isPackStage: Boolean
        get() = hasStageLock && document?.state == DocumentState.PACKING

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

    /**
     * True when any line has `actual > plan` on a type that neither tolerates
     * over-plan nor omits plans. Drives the red-highlight UX on both the line
     * cards and the summary header. Receipts (allowsOverPlan) and stock counts
     * (!requiresPlan) are excluded so they don't flag as errors.
     */
    val hasOvercollectedLine: Boolean
        get() = requiresPlan && !allowsOverPlan && lines.any {
            it.plannedQuantity > 0 && it.actualQuantity > it.plannedQuantity
        }

    /** Pack stage can be completed only when at least one parcel has been added. */
    val canCompletePack: Boolean
        get() = isPackStage && parcelCount >= 1

    /** True while a parcel weight dialog is open — scans are suspended. */
    val isAwaitingWeight: Boolean get() = pendingWeightBox != null

    /** Count of lines still pending acknowledgement. Drives the unchecked filter. */
    val uncheckedCount: Int get() = lines.count { !it.isCompleted }

    /**
     * Lines actually rendered in the list. When the unchecked filter is on
     * we drop already-completed rows; otherwise the full list is shown.
     */
    val visibleLines: List<DocumentLine>
        get() = if (showOnlyUnchecked) lines.filter { !it.isCompleted } else lines
}
