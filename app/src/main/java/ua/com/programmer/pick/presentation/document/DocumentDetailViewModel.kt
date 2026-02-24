package ua.com.programmer.pick.presentation.document

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import ua.com.programmer.pick.data.remote.websocket.DocumentLineUpdate
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.DocumentType
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        const val ERROR_LOADING_DOCUMENT = "ERROR_LOADING_DOCUMENT"
    }

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<DocumentDetailUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    private var currentDocumentId: String? = null
    private var syncDebounceJob: Job? = null

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
                val doc = documentRepository.getDocumentById(documentId)
                val lines = documentRepository.getLinesByDocumentId(documentId).first()

                // Load product images
                val productIds = lines.map { it.productId }
                val images = productImageDao.getByProductIds(productIds)
                val imagesMap = images.associate { entity ->
                    entity.productId to ProductImage(
                        id = entity.id,
                        productId = entity.productId,
                        url = entity.url,
                        base64 = entity.base64
                    )
                }

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

            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = ERROR_LOADING_DOCUMENT, isLoading = false) }
            }
        }
    }

    private fun subscribeToScans() {
        Log.d("DocumentDetailViewModel", "subscribeToScans")
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
            DocumentType.INCOMING_RECEIPT,
            DocumentType.OUTGOING_SHIPMENT -> {
                // Search line by productId first, then by productCode
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

                Log.d("DocumentDetailViewModel", "handleScannedBarcode: line=$line")

                if (line != null) {
                    // Check if already fully collected
                    if (line.plannedQuantity > 0 && line.actualQuantity >= line.plannedQuantity) {
                        // Highlight the line but do not increment
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

                    // Update UI immediately: select line and update quantity
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
                        notifyDocumentLinesChanged()
                    } catch (_: Exception) {
                        // Fallback to updateLine
                        try {
                            documentRepository.updateLine(line.id, newQty, null)
                            notifyDocumentLinesChanged()
                        } catch (_: Exception) {
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                        }
                    }
                } else {
                    Log.d("DocumentDetailViewModel", "handleScannedBarcode: line not found")
                    _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_NOT_IN_DOCUMENT))
                }
            }

            DocumentType.INVENTORY -> {
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
                        notifyDocumentLinesChanged()
                    } catch (_: Exception) {
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                    }
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
                _uiState.update { it.copy(isSaving = false) }
                notifyDocumentLinesChanged()
            } catch (ex: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = (ex.message ?: ToastMessage.ERROR_SAVING) as String?) }
            }
        }
    }

    fun takeIntoWork() {
        val documentId = currentDocumentId ?: return
        val currentState = _uiState.value.document?.state ?: return

        // Only allow taking LOADED documents
        if (currentState != DocumentState.LOADED) {
            viewModelScope.launch {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_ALREADY_TAKEN))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingAction = true) }

            try {
                val user = userRepository.getCurrentUser().first()
                val userId = user?.id ?: return@launch

                when (val result = documentRepository.takeIntoWork(documentId, userId)) {
                    is Result.Success -> {
                        _uiState.update {
                            it.copy(
                                document = result.data,
                                isProcessingAction = false
                            )
                        }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_INTO_WORK))

                        // Notify server (fire-and-forget, offline queue handles disconnected state)
                        viewModelScope.launch(ioDispatcher) {
                            try {
                                syncOrchestrator.lockDocument(documentId)
                            } catch (e: Exception) {
                                Log.w("DocumentDetailViewModel", "Failed to sync lock: ${e.message}")
                            }
                        }
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_TAKE_INTO_WORK))
                    }
                    else -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                    }
                }
            } catch (e: Exception) {
                Log.e("DocumentDetailViewModel", "takeIntoWork failed", e)
                _uiState.update { it.copy(isProcessingAction = false) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_TAKE_INTO_WORK))
            }
        }
    }

    fun completeDocument() {
        val documentId = currentDocumentId ?: return
        val currentState = _uiState.value.document?.state ?: return

        // Only allow completing IN_PROGRESS documents
        if (currentState != DocumentState.IN_PROGRESS) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingAction = true) }

            try {
                when (val result = documentRepository.completeDocument(documentId)) {
                    is Result.Success -> {
                        _uiState.update {
                            it.copy(
                                document = result.data,
                                isProcessingAction = false
                            )
                        }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_COMPLETED))

                        // Notify server (fire-and-forget)
                        viewModelScope.launch(ioDispatcher) {
                            try {
                                syncOrchestrator.completeDocument(documentId)
                            } catch (e: Exception) {
                                Log.w("DocumentDetailViewModel", "Failed to sync complete: ${e.message}")
                            }
                        }
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_COMPLETE_DOCUMENT))
                    }
                    else -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                    }
                }
            } catch (e: Exception) {
                Log.e("DocumentDetailViewModel", "completeDocument failed", e)
                _uiState.update { it.copy(isProcessingAction = false) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_COMPLETE_DOCUMENT))
            }
        }
    }

    /**
     * Debounced push of document line changes to server.
     * Batches rapid scans into a single update after 500ms of inactivity.
     */
    private fun notifyDocumentLinesChanged() {
        syncDebounceJob?.cancel()
        syncDebounceJob = viewModelScope.launch(ioDispatcher) {
            delay(500L)
            val state = _uiState.value
            val doc = state.document ?: return@launch
            val lines = state.lines.map { line ->
                DocumentLineUpdate(
                    lineNumber = line.lineNumber,
                    actualQuantity = line.actualQuantity,
                    batchNumber = line.batchNumber,
                    isCompleted = line.isCompleted
                )
            }
            try {
                syncOrchestrator.updateDocument(doc.id, doc.state.name, lines)
            } catch (e: Exception) {
                Log.w("DocumentDetailViewModel", "Failed to sync lines: ${e.message}")
            }
        }
    }
}
