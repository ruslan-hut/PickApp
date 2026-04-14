package ua.com.programmer.pick.presentation.document

import ua.com.programmer.pick.core.util.AppLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.scanner.ScannedBarcode
import ua.com.programmer.pick.data.local.database.dao.ProductImageDao
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.debug.DebugEventType
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.ProductImage
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.ProductRepository
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val userRepository: UserRepository,
    private val productImageDao: ProductImageDao,
    private val barcodeService: BarcodeService,
    private val productRepository: ProductRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val debugJournal: DebugJournal,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        const val ERROR_LOADING_DOCUMENT = "ERROR_LOADING_DOCUMENT"
        // TODO(legacy): hardcoded type check — should be replaced with a server-driven
        //  behavior flag (e.g. "allows_new_lines") once document type metadata is available.
        private const val DOCUMENT_TYPE_INVENTORY = "INVENTORY"
    }

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<DocumentDetailUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    private var currentDocumentId: String? = null

    init {
        // Subscribe to barcode scans once when ViewModel is created
        subscribeToScans()
        // Load current user
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            try {
                val user = userRepository.getCurrentUser().first()
                _uiState.update { it.copy(currentUserId = user?.id) }
            } catch (_: Exception) {
                // Ignore errors loading user
            }
        }
    }

    fun load(documentId: String) {
        // Reset state completely when loading a different document
        if (currentDocumentId != documentId) {
            _uiState.value = DocumentDetailUiState(
                currentUserId = _uiState.value.currentUserId,
                isLoading = true
            )
        } else {
            _uiState.update { it.copy(isLoading = true) }
        }

        currentDocumentId = documentId

        viewModelScope.launch {
            try {
                // Request products for this document from server (async, updates local DB)
                syncOrchestrator.requestDocumentProducts(documentId)

                val doc = documentRepository.getDocumentById(documentId)
                val lines = documentRepository.getLinesByDocumentId(documentId).first()

                // Load product images from local DB
                val productIds = lines.map { it.productId }
                val imagesMap = loadProductImages(productIds)

                _uiState.update {
                    it.copy(
                        document = doc,
                        lines = lines,
                        productImages = imagesMap,
                        isLoading = false,
                        isProcessingAction = false,
                        isSaving = false,
                        errorMessage = null,
                        selectedLineId = null
                    )
                }

                // Reload images after sync completes (products may arrive after initial load)
                reloadImagesAfterSync(productIds)

            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = ERROR_LOADING_DOCUMENT, isLoading = false) }
            }
        }
    }

    private suspend fun loadProductImages(productIds: List<String>): Map<String, ProductImage> {
        val images = productImageDao.getByProductIds(productIds)
        return images.associate { entity ->
            entity.productId to ProductImage(
                id = entity.id,
                productId = entity.productId,
                url = entity.url,
                base64 = entity.base64
            )
        }
    }

    private fun reloadImagesAfterSync(productIds: List<String>) {
        viewModelScope.launch {
            // Wait briefly for sync data to arrive and be applied
            kotlinx.coroutines.delay(2000)
            val imagesMap = loadProductImages(productIds)
            if (imagesMap.isNotEmpty()) {
                _uiState.update { it.copy(productImages = imagesMap) }
            }
        }
    }

    private fun subscribeToScans() {
        AppLog.d("DocumentDetailViewModel", "subscribeToScans")
        barcodeService.scannedBarcodes
            .onEach { scanned ->
                handleScannedBarcode(scanned)
            }
            .launchIn(viewModelScope)
    }

    private suspend fun handleScannedBarcode(scanned: ScannedBarcode) {
        val doc = _uiState.value.document ?: return
        val docId = doc.id

        // Check if current user can edit the document
        if (!_uiState.value.canEdit) {
            val state = _uiState.value
            AppLog.w(
                "DocumentDetailViewModel",
                "LOCK_TRACE scan-rejected: docId=$docId docState=${doc.state} assignedUserId=${doc.assignedUserId} currentUserId=${state.currentUserId} isInProcess=${DocumentState.isInProcess(doc.state)} isTakenByOther=${state.isTakenByOtherUser}"
            )
            if (_uiState.value.isTakenByOtherUser) {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_BY_OTHER))
            } else {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_EDIT_DOCUMENT))
            }
            return
        }

        // Determine identifier to search for
        val identifier = scanned.productId ?: scanned.productCode ?: scanned.gs1Data?.getProductBarcode() ?: scanned.rawValue

        when (doc.type) {
            DOCUMENT_TYPE_INVENTORY -> {
                // For inventory: first try to find existing line, then increment or add new

                // Resolve product info
                val productId = scanned.productId ?: run {
                    // Try to lookup product by barcode if not already resolved
                    try {
                        productRepository.getProductByBarcode(identifier)?.id
                    } catch (_: Exception) {
                        null
                    }
                }

                if (productId == null) {
                    _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.PRODUCT_NOT_FOUND))
                    return
                }

                // Check if product already exists in document lines
                val existingLine = _uiState.value.lines.find { it.productId == productId }

                if (existingLine != null) {
                    // Increment existing line quantity
                    val newQty = existingLine.actualQuantity + 1.0
                    _uiState.update { current ->
                        val updated = current.lines.map {
                            if (it.id == existingLine.id) it.copy(actualQuantity = newQty) else it
                        }
                        val newTotalActual = updated.sumOf { it.actualQuantity }
                        val updatedDocument = current.document?.copy(totalActual = newTotalActual)
                        current.copy(document = updatedDocument, lines = updated, selectedLineId = existingLine.id)
                    }

                    // Persist change
                    try {
                        documentRepository.incrementLineQuantity(existingLine.id, 1.0)
                        notifyDocumentLinesChanged()
                    } catch (_: Exception) {
                        try {
                            documentRepository.updateLine(existingLine.id, newQty, null)
                            notifyDocumentLinesChanged()
                        } catch (_: Exception) {
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                        }
                    }
                } else {
                    // Add new line for this product
                    val product = if (scanned.isKnownProduct && scanned.productId != null) {
                        // Use info from scanned barcode
                        null
                    } else {
                        // Lookup full product info
                        try {
                            productRepository.getProductByBarcode(identifier)
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val newLine = DocumentLine(
                        id = java.util.UUID.randomUUID().toString(),
                        documentId = docId,
                        lineNumber = (_uiState.value.lines.maxOfOrNull { it.lineNumber } ?: 0) + 1,
                        productId = productId,
                        productCode = product?.code ?: scanned.productCode ?: identifier,
                        productName = product?.name ?: scanned.productName ?: scanned.productCode ?: identifier,
                        unit = product?.unit ?: "pcs",
                        plannedQuantity = 0.0,
                        actualQuantity = 1.0,
                        batchNumber = null,
                        expirationDate = null,
                        locationId = null,
                        locationPath = null,
                        notes = null,
                        isCompleted = false,
                        isDirty = true
                    )

                    _uiState.update { current ->
                        val updated = current.lines + newLine
                        val newTotalActual = updated.sumOf { it.actualQuantity }
                        val updatedDocument = current.document?.copy(totalActual = newTotalActual)
                        current.copy(document = updatedDocument, lines = updated, selectedLineId = newLine.id)
                    }

                    try {
                        documentRepository.saveLine(newLine)
                        debugJournal.log(
                            eventType = DebugEventType.LINE_CREATE,
                            message = "new line from barcode scan",
                            documentId = docId,
                            payload = mapOf("line_id" to newLine.id, "qty" to newLine.actualQuantity, "barcode" to identifier)
                        )
                        notifyDocumentLinesChanged()
                    } catch (_: Exception) {
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                    }
                }
            }

            else -> {
                // Default: search line by productId first, then by productCode
                var line: DocumentLine? = null
                if (scanned.productId != null) {
                    try {
                        line = documentRepository.getLineByProductId(docId, scanned.productId)
                    } catch (_: Exception) {
                    }
                }

                if (line == null) {
                    try {
                        line = documentRepository.getLineByProductCode(docId, identifier)
                    } catch (_: Exception) {
                    }
                }

                AppLog.d("DocumentDetailViewModel", "handleScannedBarcode: line=$line")

                if (line != null) {
                    // Check if already fully collected
                    if (line.plannedQuantity > 0 && line.actualQuantity >= line.plannedQuantity) {
                        _uiState.update { current -> current.copy(selectedLineId = line.id) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_ALREADY_COMPLETED))
                        return
                    }

                    // Cap at planned quantity
                    val newQty = if (line.plannedQuantity > 0) {
                        (line.actualQuantity + 1.0).coerceAtMost(line.plannedQuantity)
                    } else {
                        line.actualQuantity + 1.0
                    }

                    // Update UI immediately
                    _uiState.update { current ->
                        val updated = current.lines.map { if (it.id == line.id) it.copy(actualQuantity = newQty) else it }
                        val newTotalActual = updated.sumOf { it.actualQuantity }
                        val updatedDocument = current.document?.copy(totalActual = newTotalActual)
                        current.copy(document = updatedDocument, lines = updated, selectedLineId = line.id)
                    }

                    // Persist change
                    val delta = newQty - line.actualQuantity
                    try {
                        documentRepository.incrementLineQuantity(line.id, delta)
                        debugJournal.log(
                            eventType = DebugEventType.LINE_EDIT,
                            message = "line increment from barcode scan",
                            documentId = docId,
                            payload = mapOf("line_id" to line.id, "delta" to delta, "new_qty" to newQty, "barcode" to identifier)
                        )
                        notifyDocumentLinesChanged()
                    } catch (_: Exception) {
                        try {
                            documentRepository.updateLine(line.id, newQty, null)
                            notifyDocumentLinesChanged()
                        } catch (_: Exception) {
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                        }
                    }
                } else {
                    AppLog.d("DocumentDetailViewModel", "handleScannedBarcode: line not found")
                    _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_NOT_IN_DOCUMENT))
                }
            }
        }
    }

    fun updateLineQuantity(lineId: String, newQuantity: Double) {
        // Check if current user can edit the document
        if (!_uiState.value.canEdit) {
            viewModelScope.launch {
                if (_uiState.value.isTakenByOtherUser) {
                    _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_BY_OTHER))
                } else {
                    _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_EDIT_DOCUMENT))
                }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { current ->
                val updated = current.lines.map { if (it.id == lineId) it.copy(actualQuantity = newQuantity) else it }
                val newTotalActual = updated.sumOf { it.actualQuantity }
                val updatedDocument = current.document?.copy(totalActual = newTotalActual)
                // Clear selection when manually editing quantity
                current.copy(document = updatedDocument, lines = updated, isSaving = true, selectedLineId = null)
            }

            try {
                documentRepository.updateLine(lineId, newQuantity, null)
                debugJournal.log(
                    eventType = DebugEventType.LINE_EDIT,
                    message = "manual quantity edit",
                    documentId = currentDocumentId,
                    payload = mapOf("line_id" to lineId, "new_qty" to newQuantity)
                )
                _uiState.update { it.copy(isSaving = false) }
                notifyDocumentLinesChanged()
            } catch (ex: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = (ex.message ?: ToastMessage.ERROR_SAVING) as String?) }
            }
        }
    }

    fun takeIntoWork() {
        // Guard against re-entry from rapid double-clicks: if a lock request
        // is already in flight, ignore this invocation.
        if (_uiState.value.isProcessingAction) return

        val documentId = currentDocumentId ?: return
        val currentState = _uiState.value.document?.state ?: return

        val stage = DocumentState.stageOf(currentState)
        if (stage == null || (!DocumentState.isStageStart(currentState) && !DocumentState.isInProcess(currentState))) {
            viewModelScope.launch {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_ALREADY_TAKEN))
            }
            return
        }

        _uiState.update { it.copy(isProcessingAction = true) }

        viewModelScope.launch {
            try {
                val lockResult = syncOrchestrator.lockForStage(documentId, stage)

                when (lockResult) {
                    is Result.Success -> {
                        val data = lockResult.data
                        val currentUserId = _uiState.value.currentUserId
                        val lockedBySelf = data.lockedBy != null && data.lockedBy == currentUserId

                        // Reload document from local DB (lockForStage persists
                        // the assigned user even on success=false, so canEdit
                        // will correctly reflect lockedBySelf).
                        val updatedDoc = documentRepository.getDocumentById(documentId)
                        AppLog.i(
                            "DocumentDetailViewModel",
                            "LOCK_TRACE vm-result: docId=$documentId success=${data.success} respLockedBy=${data.lockedBy} currentUserId=$currentUserId lockedBySelf=$lockedBySelf reloadedState=${updatedDoc?.state} reloadedAssignedUserId=${updatedDoc?.assignedUserId} idsEqual=${updatedDoc?.assignedUserId == currentUserId}"
                        )
                        _uiState.update {
                            it.copy(document = updatedDoc, isProcessingAction = false)
                        }

                        when {
                            data.success || lockedBySelf -> {
                                // Either freshly locked for us, or already ours — treat as success.
                                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_INTO_WORK))
                            }
                            data.lockedBy != null -> {
                                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_BY_OTHER))
                            }
                            else -> {
                                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_TAKE_INTO_WORK))
                            }
                        }
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        val errorMsg = lockResult.message ?: ""
                        if (errorMsg.contains("locked", ignoreCase = true) || errorMsg.contains("taken", ignoreCase = true)) {
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_BY_OTHER))
                        } else {
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_TAKE_INTO_WORK))
                        }
                    }
                    else -> _uiState.update { it.copy(isProcessingAction = false) }
                }
            } catch (e: Exception) {
                AppLog.e("DocumentDetailViewModel", "takeIntoWork failed", e)
                _uiState.update { it.copy(isProcessingAction = false) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_TAKE_INTO_WORK))
            }
        }
    }

    /**
     * Complete the current stage via server. Server transitions the document state.
     */
    fun completeDocument() {
        val documentId = currentDocumentId ?: return
        val currentState = _uiState.value.document?.state ?: return
        val stage = DocumentState.stageOf(currentState) ?: return

        if (!DocumentState.isInProcess(currentState)) return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingAction = true) }

            try {
                when (val result = syncOrchestrator.completeStage(documentId, stage)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_COMPLETED))
                        _uiEvents.emit(DocumentDetailUiEvent.NavigateBack)
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_COMPLETE_DOCUMENT))
                    }
                    else -> _uiState.update { it.copy(isProcessingAction = false) }
                }
            } catch (e: Exception) {
                AppLog.e("DocumentDetailViewModel", "completeDocument failed", e)
                _uiState.update { it.copy(isProcessingAction = false) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_COMPLETE_DOCUMENT))
            }
        }
    }

    /** Alias for completeDocument — used by save/complete button. */
    fun saveAndComplete() = completeDocument()

    /**
     * Ask the server to release (unlock) the document so the user can navigate back.
     */
    fun tryReleaseDocument() {
        val documentId = currentDocumentId ?: return
        val currentState = _uiState.value.document?.state ?: return
        val stage = DocumentState.stageOf(currentState) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingAction = true) }
            try {
                when (syncOrchestrator.unlockFromStage(documentId, stage)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.NavigateBack)
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_RELEASE_DOCUMENT))
                    }
                    else -> _uiState.update { it.copy(isProcessingAction = false) }
                }
            } catch (e: Exception) {
                AppLog.e("DocumentDetailViewModel", "tryReleaseDocument failed", e)
                _uiState.update { it.copy(isProcessingAction = false) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_RELEASE_DOCUMENT))
            }
        }
    }

    /**
     * Schedule document sync via SyncOrchestrator (application scope).
     * Debounce and actual send happen in SyncOrchestrator, so the sync
     * survives ViewModel destruction (e.g., user navigates back quickly).
     */
    private fun notifyDocumentLinesChanged() {
        val docId = currentDocumentId ?: return
        syncOrchestrator.scheduleDocumentSync(docId)
    }
}
