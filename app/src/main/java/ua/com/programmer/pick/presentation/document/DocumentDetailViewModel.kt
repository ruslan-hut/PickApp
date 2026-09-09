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
import ua.com.programmer.pick.core.util.ImageCompressor
import ua.com.programmer.pick.core.util.LinePhotoStore
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.debug.DebugEventType
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.mapper.toDocumentBoxDomainList
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.repository.DocumentTypeConfigProvider
import ua.com.programmer.pick.data.sync.DocumentMissingOnServerException
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.data.local.database.dao.BoxDao
import ua.com.programmer.pick.data.local.database.dao.DocumentBoxDao
import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.model.Box
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.ProductImage
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.ProductRepository
import javax.inject.Inject

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val productImageDao: ProductImageDao,
    private val barcodeService: BarcodeService,
    private val productRepository: ProductRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val debugJournal: DebugJournal,
    private val boxDao: BoxDao,
    private val documentBoxDao: DocumentBoxDao,
    private val documentTypeConfigProvider: DocumentTypeConfigProvider,
    private val imageCompressor: ImageCompressor,
    private val linePhotoStore: LinePhotoStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        const val ERROR_LOADING_DOCUMENT = "ERROR_LOADING_DOCUMENT"
    }

    // Capability flags for the types this user may work with. Refreshed on every
    // login (via AppPreferences) so ERP updates propagate without relaunch.
    @Volatile
    private var documentTypeConfigs: Map<String, AvailableDocumentType> = emptyMap()

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<DocumentDetailUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    private var currentDocumentId: String? = null
    private var boxesSubscriptionJob: kotlinx.coroutines.Job? = null

    init {
        // Subscribe to barcode scans once when ViewModel is created
        subscribeToScans()
        subscribeToDocumentTypeConfigs()
        subscribeToOrchestratorEvents()
    }

    /**
     * Surface orchestrator-side one-shot events into the document detail
     * stream. Only events that match the currently-loaded document are
     * forwarded, so a LockLost for a different doc (rare but possible if
     * the user back-navigated mid-recovery) doesn't fire a stale banner.
     */
    private fun subscribeToOrchestratorEvents() {
        syncOrchestrator.docSyncEvents
            .onEach { event ->
                when (event) {
                    is SyncOrchestrator.DocSyncEvent.LockLost -> {
                        if (event.documentId == currentDocumentId) {
                            _uiState.update { it.copy(hasStageLock = false) }
                            _uiEvents.emit(
                                DocumentDetailUiEvent.LockLost(
                                    droppedLineCount = event.droppedLineCount,
                                    droppedActualSum = event.droppedActualSum,
                                )
                            )
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun subscribeToDocumentTypeConfigs() {
        documentTypeConfigProvider.configs
            .onEach { map ->
                documentTypeConfigs = map
                // Re-resolve for the currently loaded doc so flag changes
                // (ERP pushed new config, user logged in again) propagate
                // without requiring a screen reopen.
                val docType = _uiState.value.document?.type ?: return@onEach
                val cfg = documentTypeConfigs[docType]
                _uiState.update {
                    it.copy(
                        allowsOverPlan = cfg?.allowsOverPlanOrDefault() ?: false,
                        allowsExtraLines = cfg?.allowsExtraLinesOrDefault() ?: false,
                        requiresPlan = cfg?.requiresPlanOrDefault() ?: true
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun resolveTypeConfig(docType: String?): AvailableDocumentType? =
        docType?.let { documentTypeConfigs[it] }

    fun load(documentId: String) {
        // Re-entry from config change (screen rotation): the ViewModel survived
        // and already holds the loaded doc plus any hasStageLock claim. Bail
        // before clearing state so the device doesn't drop a server-held lock.
        if (currentDocumentId == documentId && _uiState.value.document != null) {
            return
        }

        // Every document open starts in the "unlocked" state — the worker
        // must retake the document via STAGE_LOCK before the UI enters edit
        // mode. Lock claims are never persisted across document open/close
        // (see feedback memory "Document lock state is never client-persisted").
        syncOrchestrator.clearStageLockClaim(documentId)

        // Reset state completely when loading a different document.
        if (currentDocumentId != documentId) {
            _uiState.value = DocumentDetailUiState(isLoading = true)
        } else {
            _uiState.update { it.copy(isLoading = true, hasStageLock = false) }
        }

        currentDocumentId = documentId

        viewModelScope.launch {
            try {
                // Request products for this document from server (async, updates local DB)
                syncOrchestrator.requestDocumentProducts(documentId)

                val doc = documentRepository.getDocumentById(documentId)
                // Recover a dropped photo_path from the on-disk cache: a
                // complete-set sync can delete+recreate a line row (losing the
                // device-local column) while the file survives, so a saved photo
                // stays viewable in preview mode.
                val lines = documentRepository.getLinesByDocumentId(documentId).first().let { raw ->
                    withContext(ioDispatcher) {
                        raw.map { line ->
                            if (line.photoPath == null) {
                                linePhotoStore.findFor(line.id)?.let { line.copy(photoPath = it) } ?: line
                            } else line
                        }
                    }
                }

                // Load product images from local DB
                val productIds = lines.map { it.productId }
                val imagesMap = loadProductImages(productIds)

                val cfg = resolveTypeConfig(doc?.type)
                _uiState.update {
                    it.copy(
                        document = doc,
                        lines = lines,
                        productImages = imagesMap,
                        isLoading = false,
                        isProcessingAction = false,
                        isSaving = false,
                        errorMessage = null,
                        selectedLineId = null,
                        allowsOverPlan = cfg?.allowsOverPlanOrDefault() ?: false,
                        allowsExtraLines = cfg?.allowsExtraLinesOrDefault() ?: false,
                        requiresPlan = cfg?.requiresPlanOrDefault() ?: true
                    )
                }

                // Reload images after sync completes (products may arrive after initial load)
                reloadImagesAfterSync(productIds)

                // Resubscribe to the document_boxes flow. The DAO query sorts
                // parcels first (ORDER BY is_parcel DESC) so the UI can render
                // the list directly without a client-side sort pass.
                boxesSubscriptionJob?.cancel()
                boxesSubscriptionJob = documentBoxDao.getBoxesByDocumentId(documentId)
                    .onEach { entities ->
                        val domain = entities.toDocumentBoxDomainList()
                        val newIds = domain.map { it.boxId }
                            .filter { it !in _uiState.value.boxNamesById }
                            .toSet()
                        val added = if (newIds.isEmpty()) emptyMap()
                        else newIds.mapNotNull { id ->
                            boxDao.getBoxById(id)?.name?.let { id to it }
                        }.toMap()
                        _uiState.update { state ->
                            state.copy(
                                documentBoxes = domain,
                                boxNamesById = if (added.isEmpty()) state.boxNamesById
                                else state.boxNamesById + added
                            )
                        }
                    }
                    .launchIn(viewModelScope)

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

        // Not in an in-process state → can't edit. Per the server-driven rule
        // the app never sees another worker's in-process doc, so this branch
        // means the doc is still in its stage start (LOADED / PACK) and needs
        // to be taken into work first.
        if (!_uiState.value.canEdit) {
            AppLog.w(
                "DocumentDetailViewModel",
                "scan-rejected: docId=$docId docState=${doc.state} isInProcess=${DocumentState.isInProcess(doc.state)}"
            )
            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_EDIT_DOCUMENT))
            return
        }

        // Block scans while the parcel-weight dialog is open — the worker must
        // finish entering the previous parcel's weight before starting the next
        // scan. This protects against a rapid-fire scanner double-read.
        if (_uiState.value.isAwaitingWeight) {
            AppLog.d("DocumentDetailViewModel", "scan suppressed: parcel weight dialog open")
            return
        }

        // Determine identifier to search for
        val identifier = scanned.productId ?: scanned.productCode ?: scanned.gs1Data?.getProductBarcode() ?: scanned.rawValue

        // Pack stage: try box catalog first, then fall back to product flow.
        // During PACKING the products list is read-only, so a product barcode
        // here only surfaces a "not in document" alert instead of incrementing
        // any line.
        if (_uiState.value.isPackStage) {
            if (handleBoxScanAttempt(identifier)) return
            // Box not found → try product lookup for user feedback only.
            val productLine = _uiState.value.lines.firstOrNull { it.productCode == identifier || it.productId == identifier }
            if (productLine != null) {
                _uiState.update { it.copy(selectedLineId = productLine.id) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_ALREADY_COMPLETED))
            } else {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.BOX_NOT_FOUND))
            }
            return
        }

        // Per-line ERP scan codes come first. On an e-excise document the same
        // product repeats on every line, so a product lookup could not tell the
        // lines apart — only the stamp code can. A code shared by several lines
        // is a group package: all of them close at once.
        if (completeLinesByScanCode(docId, listOf(scanned.rawValue, identifier))) return

        // No line matched. On a document whose lines carry their own codes the
        // product catalogue is not an acceptable fallback — every line may share
        // one product, so a product hit would close an arbitrary stamp.
        if (documentRepository.hasLineBarcodes(docId)) {
            _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_NOT_IN_DOCUMENT))
            return
        }

        if (_uiState.value.allowsExtraLines) {
            // The document type permits scanning products that aren't in the
            // pre-loaded line set — the client creates a new line on the fly.
            // Historically hardcoded to INVENTORY; now driven by the ERP-set
            // `allows_extra_lines` flag so receipts or counts can opt in.

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
                    applyLineQtyOptimistically(existingLine.id, newQty)

                    // Persist change — and revert the optimistic update if the DB
                    // rejects it. Without this the UI drifts silently above what
                    // the server ever sees.
                    val result = documentRepository.incrementLineQuantity(existingLine.id, 1.0)
                    when (result) {
                        is Result.Success -> {
                            maybeAutoMarkCompleted(existingLine.id, newQty)
                            notifyDocumentLinesChanged()
                        }
                        is Result.Error -> {
                            applyLineQtyOptimistically(existingLine.id, existingLine.actualQuantity)
                            logLineEditFailure(existingLine.id, result, "inventory scan", newQty)
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                        }
                        else -> Unit
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
        } else {
                // Default: line must already exist in the document; search by
                // productId first, then by productCode.
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

                // The scan never resolved to a catalogue product AND no line
                // matched the raw code. Most often the product's barcode rows
                // simply haven't synced yet (incomplete/late product sync — the
                // classic "restart fixes scanning" symptom). Resolve the barcode
                // on demand and retry before deciding the item isn't here.
                if (line == null && scanned.productId == null) {
                    val resolvedId = resolveScannedProduct(scanned)
                    if (resolvedId == null) {
                        // Barcode is genuinely unknown to the ERP — a catalogue
                        // gap, not a "wrong document" situation. Say so instead
                        // of the misleading "not in this document".
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.PRODUCT_NOT_FOUND))
                        return
                    }
                    try {
                        line = documentRepository.getLineByProductId(docId, resolvedId)
                    } catch (_: Exception) {
                    }
                }

                AppLog.d("DocumentDetailViewModel", "handleScannedBarcode: line=$line")

                if (line != null) {
                    val allowOver = _uiState.value.allowsOverPlan
                    // Block further scans once a line has reached its plan — unless the
                    // document type legitimately allows over-plan (e.g. INCOMING_RECEIPT
                    // over-delivery), in which case +1 keeps accumulating.
                    if (!allowOver && line.plannedQuantity > 0 && line.actualQuantity >= line.plannedQuantity) {
                        _uiState.update { current -> current.copy(selectedLineId = line.id) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_ALREADY_COMPLETED))
                        return
                    }

                    // Cap at planned quantity (when applicable); over-plan docs are uncapped.
                    val newQty = if (line.plannedQuantity > 0 && !allowOver) {
                        (line.actualQuantity + 1.0).coerceAtMost(line.plannedQuantity)
                    } else {
                        line.actualQuantity + 1.0
                    }

                    // Update UI immediately (optimistic).
                    applyLineQtyOptimistically(line.id, newQty, selectLineId = true)

                    // Persist change — revert the optimistic update if the DB
                    // rejects it (stale line id after resync, or real IO error).
                    val delta = newQty - line.actualQuantity
                    val result = documentRepository.incrementLineQuantity(line.id, delta)
                    when (result) {
                        is Result.Success -> {
                            debugJournal.log(
                                eventType = DebugEventType.LINE_EDIT,
                                message = "line increment from barcode scan",
                                documentId = docId,
                                payload = mapOf("line_id" to line.id, "delta" to delta, "new_qty" to newQty, "barcode" to identifier)
                            )
                            maybeAutoMarkCompleted(line.id, newQty)
                            notifyDocumentLinesChanged()
                        }
                        is Result.Error -> {
                            applyLineQtyOptimistically(line.id, line.actualQuantity)
                            logLineEditFailure(line.id, result, "barcode scan", newQty, extra = mapOf("barcode" to identifier))
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                        }
                        else -> Unit
                    }
                } else {
                    AppLog.d("DocumentDetailViewModel", "handleScannedBarcode: line not found")
                    _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_NOT_IN_DOCUMENT))
                }
        }
    }

    /**
     * Resolve a scanned barcode that missed the local product catalogue. Tries
     * the local cache once more (a concurrent sync may have filled it), then
     * asks the server via an on-demand PRODUCT_LOOKUP and re-reads the cache.
     * Returns the resolved local product id, or null if the barcode is unknown
     * to the ERP. The barcode key mirrors BarcodeService.enrichWithProductInfo
     * (GS1 product code when present, otherwise the raw scan).
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun resolveScannedProduct(scanned: ScannedBarcode): String? {
        val searchBarcode = scanned.gs1Data?.getProductBarcode() ?: scanned.rawValue
        if (searchBarcode.isBlank()) return null

        // A product sync may have landed since the scan was first enriched.
        runCatching { productRepository.getProductByBarcode(searchBarcode) }
            .getOrNull()?.let { return it.id }

        // Ask the server to resolve it, then re-read the freshly-stored row.
        if (!syncOrchestrator.lookupProductByBarcode(searchBarcode)) return null
        return runCatching { productRepository.getProductByBarcode(searchBarcode) }
            .getOrNull()?.id
    }

    fun updateLineQuantity(lineId: String, newQuantity: Double) {
        if (!_uiState.value.canEdit) {
            viewModelScope.launch {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_EDIT_DOCUMENT))
            }
            return
        }

        // Snapshot the prior value so we can roll back the optimistic UI
        // update if the persist step fails. Without this, a failed save
        // leaves the UI showing a higher "Fact" total than the DB/server.
        val priorQty = _uiState.value.lines.find { it.id == lineId }?.actualQuantity

        viewModelScope.launch {
            _uiState.update { current ->
                val updated = current.lines.map { if (it.id == lineId) it.copy(actualQuantity = newQuantity) else it }
                val newTotalActual = updated.sumOf { it.actualQuantity }
                val updatedDocument = current.document?.copy(totalActual = newTotalActual)
                // Clear selection when manually editing quantity
                current.copy(document = updatedDocument, lines = updated, isSaving = true, selectedLineId = null)
            }

            val result = documentRepository.updateLine(lineId, newQuantity, null)
            when (result) {
                is Result.Success -> {
                    debugJournal.log(
                        eventType = DebugEventType.LINE_EDIT,
                        message = "manual quantity edit",
                        documentId = currentDocumentId,
                        payload = mapOf("line_id" to lineId, "new_qty" to newQuantity)
                    )
                    _uiState.update { it.copy(isSaving = false) }
                    maybeAutoMarkCompleted(lineId, newQuantity)
                    notifyDocumentLinesChanged()
                }
                is Result.Error -> {
                    if (priorQty != null) {
                        applyLineQtyOptimistically(lineId, priorQty)
                    }
                    _uiState.update { it.copy(isSaving = false) }
                    logLineEditFailure(lineId, result, "manual edit", newQuantity)
                    _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                }
                else -> _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun updateLineNote(lineId: String, text: String) {
        if (!_uiState.value.canEditLines) {
            viewModelScope.launch {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_EDIT_DOCUMENT))
            }
            return
        }

        val priorNote = _uiState.value.lines.find { it.id == lineId }?.notes
        if (priorNote == text) return

        viewModelScope.launch {
            _uiState.update { current ->
                val updated = current.lines.map { if (it.id == lineId) it.copy(notes = text) else it }
                current.copy(lines = updated, isSaving = true)
            }

            when (val result = documentRepository.updateLineNote(lineId, text)) {
                is Result.Success -> {
                    debugJournal.log(
                        eventType = DebugEventType.LINE_EDIT,
                        message = "line note edit",
                        documentId = currentDocumentId,
                        payload = mapOf("line_id" to lineId)
                    )
                    _uiState.update { it.copy(isSaving = false) }
                    notifyDocumentLinesChanged()
                }
                is Result.Error -> {
                    _uiState.update { current ->
                        val reverted = current.lines.map { if (it.id == lineId) it.copy(notes = priorNote) else it }
                        current.copy(lines = reverted, isSaving = false)
                    }
                    logLineEditFailure(lineId, result, "note edit")
                    _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                }
                else -> _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /** Camera bind/capture failed in the overlay — surface a toast. */
    fun notifyPhotoCaptureFailed() {
        viewModelScope.launch {
            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_PHOTO_CAPTURE))
        }
    }

    /**
     * Persist a freshly captured line photo: compress the JPEG, cache it under
     * filesDir, mark the line pending upload, then attempt an immediate upload
     * (it falls back to the reconnect drain when offline).
     */
    fun onPhotoCaptured(lineId: String, jpeg: ByteArray, rotationDegrees: Int) {
        if (!_uiState.value.canEditLines) return

        viewModelScope.launch {
            val captured = withContext(ioDispatcher) {
                val compressed = imageCompressor.compress(jpeg, rotationDegrees)
                    ?: return@withContext null
                linePhotoStore.write(lineId, compressed) to compressed.size
            }
            if (captured == null) {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_PHOTO_CAPTURE))
                return@launch
            }
            val (path, byteCount) = captured

            val result = documentRepository.updateLinePhoto(lineId, path)
            if (result is Result.Error) {
                withContext(ioDispatcher) { linePhotoStore.delete(path) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_PHOTO_CAPTURE))
                return@launch
            }

            // Drop any earlier capture for this line (incl. orphans left when a
            // sync had nulled photo_path) so exactly the new file remains.
            withContext(ioDispatcher) { linePhotoStore.deleteOthers(lineId, path) }

            // Optimistic: show the new thumbnail + pending state immediately.
            _uiState.update { current ->
                val updated = current.lines.map {
                    if (it.id == lineId) it.copy(photoPath = path, photoPending = true) else it
                }
                current.copy(lines = updated)
            }

            debugJournal.log(
                eventType = DebugEventType.LINE_EDIT,
                message = "line photo captured",
                documentId = currentDocumentId,
                payload = mapOf("line_id" to lineId, "bytes" to byteCount)
            )

            // Best-effort upload now; on failure it stays pending for reconnect.
            if (syncOrchestrator.uploadLinePhoto(lineId)) {
                _uiState.update { current ->
                    val updated = current.lines.map {
                        if (it.id == lineId) it.copy(hasPhoto = true, photoPending = false) else it
                    }
                    current.copy(lines = updated)
                }
            }
        }
    }

    /**
     * Flip `is_completed` to true when the actual quantity meets or exceeds plan.
     * For docs where `allows_over_plan` is off, the input caps elsewhere prevent
     * `actual > plan` anyway, so `>=` collapses to exact-match there. For docs
     * that allow over-delivery, the line auto-marks the moment the plan is
     * reached and additional scans keep accumulating without toggling the mark
     * back. We never auto-unset; clearing requires an explicit swipe-left.
     * Planned=0 lines (e.g. inventory) auto-mark on any positive quantity.
     */
    /**
     * Resolve the scan against the document's per-line barcodes and close every
     * line that carries it. Returns false when no line matches any candidate
     * code, leaving the caller to fall back to the product-catalogue path.
     *
     * Unlike a product scan this does not increment: a line stands for one
     * stamped unit, so it jumps straight to its planned quantity and is marked
     * done. Re-scanning an already-closed code is therefore idempotent.
     */
    private suspend fun completeLinesByScanCode(docId: String, codes: List<String>): Boolean {
        val match = codes
            .filter { it.isNotBlank() }
            .distinct()
            .firstNotNullOfOrNull { code ->
                val lines = try {
                    documentRepository.getLinesByBarcode(docId, code)
                } catch (_: Exception) {
                    emptyList()
                }
                if (lines.isEmpty()) null else code to lines
            } ?: return false

        val (code, lines) = match
        val pending = lines.filter { !it.isCompleted }
        if (pending.isEmpty()) {
            _uiState.update { it.copy(selectedLineId = lines.first().id) }
            _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.PRODUCT_ALREADY_COMPLETED))
            return true
        }

        var failed = 0
        for (line in pending) {
            val qty = if (line.plannedQuantity > 0) line.plannedQuantity else 1.0
            applyLineQtyOptimistically(line.id, qty)
            when (val result = documentRepository.updateLine(line.id, qty, null)) {
                is Result.Error -> {
                    failed++
                    applyLineQtyOptimistically(line.id, line.actualQuantity)
                    logLineEditFailure(line.id, result, "line barcode scan", qty, extra = mapOf("barcode" to code))
                }
                else -> setLineCompleted(line.id, true)
            }
        }

        _uiState.update { it.copy(selectedLineId = pending.first().id) }
        debugJournal.log(
            eventType = DebugEventType.LINE_EDIT,
            message = "closed ${pending.size - failed} line(s) from line-barcode scan",
            documentId = docId,
            payload = mapOf("barcode" to code, "matched" to lines.size, "failed" to failed)
        )
        if (failed > 0) {
            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
        }
        notifyDocumentLinesChanged()
        return true
    }

    private fun maybeAutoMarkCompleted(lineId: String, newQuantity: Double) {
        val line = _uiState.value.lines.find { it.id == lineId } ?: return
        if (line.isCompleted) return
        if (newQuantity <= 0.0) return
        if (line.plannedQuantity > 0 && newQuantity < line.plannedQuantity) return
        setLineCompleted(lineId, true)
    }

    /**
     * Toggle `is_completed` for a single line. Called by the swipe gesture and
     * by the auto-mark path. Updates the in-memory state optimistically; on a
     * DB write failure the UI is reverted so it doesn't diverge from storage.
     *
     * Refuses to mark an over-collected line as done — the user must correct
     * the quantity first. Unmarking (isCompleted=false) is always allowed.
     */
    fun setLineCompleted(lineId: String, isCompleted: Boolean) {
        val line = _uiState.value.lines.find { it.id == lineId } ?: return
        val prior = line.isCompleted
        if (prior == isCompleted) return

        if (isCompleted
            && !_uiState.value.allowsOverPlan
            && line.plannedQuantity > 0
            && line.actualQuantity > line.plannedQuantity
        ) {
            viewModelScope.launch {
                _uiEvents.emit(DocumentDetailUiEvent.ShowBarcodeAlert(BarcodeAlertType.LINE_OVERCOLLECTED))
            }
            return
        }

        _uiState.update { current ->
            val updated = current.lines.map {
                if (it.id == lineId) it.copy(isCompleted = isCompleted) else it
            }
            current.copy(lines = updated)
        }

        viewModelScope.launch {
            when (val result = documentRepository.updateLineCompleted(lineId, isCompleted)) {
                is Result.Error -> {
                    _uiState.update { current ->
                        val reverted = current.lines.map {
                            if (it.id == lineId) it.copy(isCompleted = prior) else it
                        }
                        current.copy(lines = reverted)
                    }
                    AppLog.w(
                        "DocumentDetailViewModel",
                        "setLineCompleted failed: line=$lineId target=$isCompleted reason=${result.message}"
                    )
                    _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_SAVING))
                }
                is Result.Success -> notifyDocumentLinesChanged()
                else -> Unit
            }
        }
    }

    /**
     * Toggle the "show only unchecked lines" filter. Turning the filter on
     * with no unchecked lines left is a no-op that surfaces a toast — the
     * worker would otherwise see an empty list with no explanation. Turning
     * it off is always allowed.
     */
    fun toggleUncheckedFilter() {
        val state = _uiState.value
        if (!state.showOnlyUnchecked && state.uncheckedCount == 0) {
            viewModelScope.launch {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.NO_UNCHECKED_LINES))
            }
            return
        }
        _uiState.update { it.copy(showOnlyUnchecked = !it.showOnlyUnchecked) }
    }

    /** Clear the unchecked-lines warning after the blocking dialog is dismissed. */
    fun dismissUncheckedWarning() {
        _uiState.update { it.copy(firstUncheckedLineId = null, uncheckedLineCount = 0) }
    }

    /**
     * Apply a line quantity change to the in-memory UI state, recomputing the
     * document's `totalActual` from the resulting line set. Used both for the
     * initial optimistic update and for the revert on save failure.
     */
    private fun applyLineQtyOptimistically(
        lineId: String,
        newQty: Double,
        selectLineId: Boolean = false
    ) {
        _uiState.update { current ->
            val updated = current.lines.map {
                if (it.id == lineId) it.copy(actualQuantity = newQty) else it
            }
            val newTotalActual = updated.sumOf { it.actualQuantity }
            val updatedDocument = current.document?.copy(totalActual = newTotalActual)
            current.copy(
                document = updatedDocument,
                lines = updated,
                selectedLineId = if (selectLineId) lineId else current.selectedLineId
            )
        }
    }

    private suspend fun logLineEditFailure(
        lineId: String,
        error: Result.Error,
        source: String,
        attemptedQty: Double? = null,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val payload = buildMap<String, Any?> {
            put("line_id", lineId)
            attemptedQty?.let { put("attempted_qty", it) }
            put("source", source)
            put("reason", error.message ?: error.exception.message ?: "unknown")
            putAll(extra)
        }
        debugJournal.log(
            eventType = DebugEventType.LINE_EDIT_FAILED,
            message = "line edit not persisted; UI reverted",
            documentId = currentDocumentId,
            severity = DebugJournal.SEVERITY_ERROR,
            payload = payload
        )
        AppLog.w(
            "DocumentDetailViewModel",
            "line edit failed: line=$lineId qty=$attemptedQty source=$source reason=${error.message ?: error.exception.message}"
        )
    }

    /**
     * Hands a `collect_mode: "guided"` document to the task screen. No lock is
     * claimed here — `POST /device/tasks {document_id}` takes the Collect lock
     * on the server with the same semantics, and the device must not hold a
     * classic claim on top of it (D3).
     */
    fun startGuidedTask() {
        val document = _uiState.value.document ?: return
        viewModelScope.launch {
            _uiEvents.emit(
                DocumentDetailUiEvent.NavigateToTask(
                    document.externalId?.takeIf { it.isNotBlank() } ?: document.id
                )
            )
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
                        // Server is the source of truth: `success=true` means
                        // the lock is ours (or idempotent re-lock we already
                        // held). No local "is this mine?" logic. On success
                        // the repository has already advanced the local state
                        // to stage.InProcess, so a plain reload is enough.
                        val data = lockResult.data
                        val updatedDoc = documentRepository.getDocumentById(documentId)
                        AppLog.i(
                            "DocumentDetailViewModel",
                            "lock-result: docId=$documentId success=${data.success} lockedBy=${data.lockedBy} reloadedState=${updatedDoc?.state}"
                        )
                        _uiState.update {
                            it.copy(
                                document = updatedDoc,
                                isProcessingAction = false,
                                hasStageLock = data.success
                            )
                        }

                        if (data.success) {
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_TAKEN_INTO_WORK))
                        } else {
                            // Server rejected the lock — most commonly because
                            // another worker holds it (ForceReleaseLock / race)
                            // or the doc state has advanced since our last sync.
                            val toast = if (data.error?.contains("locked", ignoreCase = true) == true) {
                                ToastMessage.DOCUMENT_TAKEN_BY_OTHER
                            } else {
                                ToastMessage.ERROR_TAKE_INTO_WORK
                            }
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(toast))
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

        // Collector must acknowledge every line before finishing. Other stages
        // (PACKING, DELIVERING, etc.) have their own completion rules and are
        // not affected by is_completed.
        if (currentState == DocumentState.COLLECTING) {
            val lines = _uiState.value.lines
            val unchecked = lines.filter { !it.isCompleted }
            if (unchecked.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        firstUncheckedLineId = unchecked.first().id,
                        uncheckedLineCount = unchecked.size
                    )
                }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingAction = true) }

            try {
                when (val result = syncOrchestrator.completeStage(documentId, stage)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isProcessingAction = false, hasStageLock = false) }
                        // A requires_review document that finished Collect with a
                        // planned/actual mismatch is parked in REVIEW, not completed.
                        val toast = if (result.data.state.equals("REVIEW", ignoreCase = true)) {
                            ToastMessage.DOCUMENT_SENT_FOR_REVIEW
                        } else {
                            ToastMessage.DOCUMENT_COMPLETED
                        }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(toast))
                        _uiEvents.emit(DocumentDetailUiEvent.NavigateBack)
                    }
                    is Result.Error -> {
                        val msg = result.message ?: result.exception.message
                        val lostLock = msg != null && (
                            msg.contains("FORBIDDEN", ignoreCase = true) ||
                                msg.contains("must be locked", ignoreCase = true)
                            )
                        _uiState.update {
                            it.copy(
                                isProcessingAction = false,
                                hasStageLock = if (lostLock) false else it.hasStageLock
                            )
                        }
                        if (result.exception is DocumentMissingOnServerException) {
                            // Server purged the document (e.g. ERP issued GONE while we
                            // were offline). Orchestrator already wiped local copy and
                            // pending ops; navigate the user back so they don't keep
                            // re-pressing complete on a phantom doc.
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_GONE_ON_SERVER))
                            _uiEvents.emit(DocumentDetailUiEvent.NavigateBack)
                        } else {
                            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.ERROR_COMPLETE_DOCUMENT))
                        }
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
     * Pause work on the document: release the session lock while the server
     * keeps the document at the current in-process state (COLLECTING /
     * PACKING). The document stays in the worker's queue and the time spent
     * paused is excluded from the worker's effective work duration. Resume is
     * an ordinary stage lock on the same document. Emits a toast and
     * navigates back on success.
     */
    fun pauseDocument() {
        if (_uiState.value.isProcessingAction) return

        val documentId = currentDocumentId ?: return
        val currentState = _uiState.value.document?.state ?: return
        val stage = DocumentState.stageOf(currentState) ?: return
        if (!DocumentState.isInProcess(currentState)) return

        _uiState.update { it.copy(isProcessingAction = true) }

        viewModelScope.launch {
            try {
                when (syncOrchestrator.pauseStage(documentId, stage)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isProcessingAction = false, hasStageLock = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.DOCUMENT_PAUSED))
                        _uiEvents.emit(DocumentDetailUiEvent.NavigateBack)
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isProcessingAction = false) }
                        _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_RELEASE_DOCUMENT))
                    }
                    else -> _uiState.update { it.copy(isProcessingAction = false) }
                }
            } catch (e: Exception) {
                AppLog.e("DocumentDetailViewModel", "pauseDocument failed", e)
                _uiState.update { it.copy(isProcessingAction = false) }
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.CANNOT_RELEASE_DOCUMENT))
            }
        }
    }

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
                        _uiState.update { it.copy(isProcessingAction = false, hasStageLock = false) }
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

    // ============================================
    // Pack-stage box operations
    // ============================================

    /**
     * Switch the visible content area between Products and Boxes.
     * Purely presentation — no server traffic.
     */
    fun setActiveTab(tab: DocumentDetailTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    /**
     * Resolve a scanned barcode against the box catalog.
     * Returns true if the barcode was recognised as a box (result: parcel
     * weight dialog opened OR package added immediately). Returns false when
     * the barcode is not a box — caller falls back to the product flow.
     */
    private suspend fun handleBoxScanAttempt(barcode: String): Boolean {
        val docId = currentDocumentId
        // 1. Local cache first (every sync pulls boxes; usually hits).
        var box: Box? = boxDao.getBoxByBarcode(barcode)?.toDomain()
        if (box == null) {
            debugJournal.log(
                eventType = DebugEventType.BOX_LOOKUP,
                message = "local cache miss; querying server",
                documentId = docId,
                payload = mapOf("barcode" to barcode, "length" to barcode.length)
            )
            // 2. Server fallback — the catalog may have grown since the last sync.
            val result = try {
                syncOrchestrator.sendBoxLookup(barcode)
            } catch (e: Exception) {
                AppLog.w("DocumentDetailViewModel", "box lookup failed: ${e.message}")
                debugJournal.log(
                    eventType = DebugEventType.BOX_LOOKUP,
                    message = "server lookup error: ${e.message}",
                    documentId = docId,
                    severity = DebugJournal.SEVERITY_WARN,
                    payload = mapOf("barcode" to barcode)
                )
                null
            }
            if (result == null) {
                debugJournal.log(
                    eventType = DebugEventType.BOX_LOOKUP,
                    message = "server lookup: no response (offline or timeout)",
                    documentId = docId,
                    severity = DebugJournal.SEVERITY_WARN,
                    payload = mapOf("barcode" to barcode)
                )
                return false
            }
            if (!result.success) {
                debugJournal.log(
                    eventType = DebugEventType.BOX_LOOKUP,
                    message = "server reported not found: ${result.error}",
                    documentId = docId,
                    severity = DebugJournal.SEVERITY_WARN,
                    payload = mapOf("barcode" to barcode, "error" to (result.error ?: ""))
                )
                return false
            }
            box = boxDao.getBoxByBarcode(barcode)?.toDomain()
            if (box == null) {
                debugJournal.log(
                    eventType = DebugEventType.BOX_LOOKUP,
                    message = "server returned success but local cache still misses after insert",
                    documentId = docId,
                    severity = DebugJournal.SEVERITY_WARN,
                    payload = mapOf("barcode" to barcode)
                )
                return false
            }
            debugJournal.log(
                eventType = DebugEventType.BOX_LOOKUP,
                message = "server resolved box and cached locally",
                documentId = docId,
                payload = mapOf("barcode" to barcode, "is_parcel" to box.isParcel, "id" to box.id)
            )
        }

        if (box.isParcel) {
            // Open the modal weight dialog — no BOX_ADD is sent until the worker
            // confirms. New scans are suspended via isAwaitingWeight.
            _uiState.update { it.copy(pendingWeightBox = box) }
        } else {
            // Packages carry no weight — add immediately.
            submitBoxAdd(box, weightGrams = 0)
        }
        return true
    }

    /** Confirm the parcel weight dialog and send BOX_ADD. Called from the UI. */
    fun confirmParcelWeight(weightGrams: Int) {
        val box = _uiState.value.pendingWeightBox ?: return
        if (weightGrams <= 0) return
        _uiState.update { it.copy(pendingWeightBox = null) }
        viewModelScope.launch { submitBoxAdd(box, weightGrams) }
    }

    /** Cancel the parcel weight dialog — no BOX_ADD is sent. */
    fun cancelParcelWeight() {
        _uiState.update { it.copy(pendingWeightBox = null) }
    }

    private suspend fun submitBoxAdd(box: Box, weightGrams: Int) {
        val docId = currentDocumentId ?: return
        val result = try {
            syncOrchestrator.sendBoxAdd(docId, box.barcode, weightGrams)
        } catch (e: Exception) {
            AppLog.e("DocumentDetailViewModel", "sendBoxAdd failed", e)
            debugJournal.log(
                eventType = DebugEventType.BOX_ADD,
                message = "box add failed: ${e.message}",
                documentId = docId,
                payload = mapOf("barcode" to box.barcode, "is_parcel" to box.isParcel, "weight" to weightGrams)
            )
            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.BOX_ADD_FAILED))
            return
        }
        if (result?.success != true) {
            AppLog.w("DocumentDetailViewModel", "BOX_ADD rejected: ${result?.error}")
            debugJournal.log(
                eventType = DebugEventType.BOX_ADD,
                message = "box add rejected: ${result?.error}",
                documentId = docId,
                payload = mapOf("barcode" to box.barcode, "is_parcel" to box.isParcel, "weight" to weightGrams)
            )
            // If the server says the document is no longer locked by this
            // user, drop the session claim so the UI flips back to "Take into
            // work" instead of silently failing every subsequent scan.
            val err = result?.error
            if (err != null && (err.contains("FORBIDDEN", ignoreCase = true) ||
                    err.contains("must be locked", ignoreCase = true))) {
                _uiState.update { it.copy(hasStageLock = false) }
            }
            _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.BOX_ADD_FAILED))
            return
        }
        // Success path: SyncOrchestrator has already upserted the DocumentBox
        // locally, so the documentBoxes flow will push the new row into the UI.
        debugJournal.log(
            eventType = DebugEventType.BOX_ADD,
            message = "box added during pack",
            documentId = docId,
            payload = mapOf("barcode" to box.barcode, "is_parcel" to box.isParcel, "weight" to weightGrams)
        )
    }

    /** Swipe-to-delete handler: remove a previously-added box during PACKING. */
    fun removeBox(boxNumber: Int) {
        if (!_uiState.value.isPackStage) return
        val docId = currentDocumentId ?: return
        viewModelScope.launch {
            val result = try {
                syncOrchestrator.sendBoxRemove(docId, boxNumber)
            } catch (e: Exception) {
                AppLog.e("DocumentDetailViewModel", "sendBoxRemove failed", e)
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.BOX_REMOVE_FAILED))
                return@launch
            }
            if (result?.success != true) {
                AppLog.w("DocumentDetailViewModel", "BOX_REMOVE rejected: ${result?.error}")
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.BOX_REMOVE_FAILED))
            }
        }
    }

    /**
     * Pack-stage completion guard: the server rejects a STAGE_COMPLETE(pack)
     * without at least one parcel. Surface this to the UI before dispatching
     * so the worker gets an immediate error instead of a WS round-trip.
     */
    fun completePackStage() {
        if (!_uiState.value.canCompletePack) {
            viewModelScope.launch {
                _uiEvents.emit(DocumentDetailUiEvent.ShowToast(ToastMessage.PACK_REQUIRES_PARCEL))
            }
            return
        }
        completeDocument()
    }
}
