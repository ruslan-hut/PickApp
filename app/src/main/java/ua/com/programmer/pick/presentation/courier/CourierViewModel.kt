package ua.com.programmer.pick.presentation.courier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.data.remote.websocket.MessageParser
import ua.com.programmer.pick.data.remote.websocket.SyncMessage
import ua.com.programmer.pick.data.remote.websocket.SyncTransport
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentBox
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.repository.BoxRepository
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.EntityType
import ua.com.programmer.pick.domain.repository.OperationType
import ua.com.programmer.pick.domain.repository.OutgoingOperationRepository
import com.google.gson.Gson
import javax.inject.Inject

enum class CourierMode { PICKUP, DELIVERY }

data class CourierUiState(
    val mode: CourierMode = CourierMode.PICKUP,
    val documents: List<Document> = emptyList(),
    val selectedDocumentId: String? = null,
    val boxes: List<DocumentBox> = emptyList(),
    val totalBoxes: Int = 0,
    val confirmedBoxes: Int = 0,
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null
)

sealed class CourierEvent {
    data class ShowMessage(val messageResId: Int) : CourierEvent()
    data object AllBoxesConfirmed : CourierEvent()
}

@HiltViewModel
class CourierViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val boxRepository: BoxRepository,
    private val webSocketManager: SyncTransport,
    private val messageParser: MessageParser,
    private val outgoingOperationRepository: OutgoingOperationRepository,
    private val barcodeService: BarcodeService,
    private val gson: Gson,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourierUiState())
    val uiState: StateFlow<CourierUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CourierEvent>()
    val events = _events.asSharedFlow()

    private var offlineSeqCounter = 0

    init {
        observePickupDocuments()
        observeScanner()
    }

    private fun observePickupDocuments() {
        viewModelScope.launch {
            documentRepository.getDocumentsByState(DocumentState.DELIVERY).collect { docs ->
                if (_uiState.value.mode == CourierMode.PICKUP) {
                    _uiState.update { it.copy(documents = docs) }
                }
            }
        }
        viewModelScope.launch {
            documentRepository.getDocumentsByState(DocumentState.DELIVERING).collect { docs ->
                if (_uiState.value.mode == CourierMode.DELIVERY) {
                    _uiState.update { it.copy(documents = docs) }
                }
            }
        }
    }

    private fun observeScanner() {
        viewModelScope.launch {
            barcodeService.scannedBarcodes.collect { scanned ->
                handleBarcodeScan(scanned.rawValue)
            }
        }
    }

    fun switchMode(mode: CourierMode) {
        _uiState.update { it.copy(mode = mode, selectedDocumentId = null, boxes = emptyList()) }
        viewModelScope.launch {
            val state = if (mode == CourierMode.PICKUP) DocumentState.DELIVERY else DocumentState.DELIVERING
            documentRepository.getDocumentsByState(state).collect { docs ->
                _uiState.update { it.copy(documents = docs) }
            }
        }
    }

    fun selectDocument(documentId: String) {
        _uiState.update { it.copy(selectedDocumentId = documentId) }
        viewModelScope.launch {
            boxRepository.getBoxesByDocumentId(documentId).collect { boxes ->
                val mode = _uiState.value.mode
                val confirmed = when (mode) {
                    CourierMode.PICKUP -> boxes.count { it.pickedUpAt != null }
                    CourierMode.DELIVERY -> boxes.count { it.deliveredAt != null }
                }
                _uiState.update {
                    it.copy(boxes = boxes, totalBoxes = boxes.size, confirmedBoxes = confirmed)
                }
            }
        }
    }

    private fun handleBarcodeScan(barcode: String) {
        val state = _uiState.value
        val documentId = state.selectedDocumentId ?: return

        when (state.mode) {
            CourierMode.PICKUP -> confirmPickup(barcode)
            CourierMode.DELIVERY -> confirmDelivery(barcode)
        }
    }

    fun confirmPickup(barcode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            offlineSeqCounter++

            val message = SyncMessage.BoxPickupConfirm(
                id = messageParser.generateMessageId(),
                timestamp = messageParser.getCurrentTimestamp(),
                barcode = barcode,
                offlineSeq = offlineSeqCounter,
                clientTs = System.currentTimeMillis()
            )

            if (webSocketManager.isConnected()) {
                val response = webSocketManager.sendAndAwait(
                    message,
                    SyncMessage.BoxPickupConfirmResult::class.java
                )

                _uiState.update { it.copy(isProcessing = false) }

                if (response?.success == true) {
                    _events.emit(CourierEvent.ShowMessage(ua.com.programmer.pick.R.string.box_picked_up))
                    checkAllConfirmed()
                } else {
                    _events.emit(CourierEvent.ShowMessage(ua.com.programmer.pick.R.string.pickup_confirm_error))
                }
            } else {
                // Queue for offline replay
                outgoingOperationRepository.queueOperation(
                    operationType = OperationType.BOX_PICKUP_CONFIRM,
                    entityType = EntityType.DOCUMENT_BOX,
                    entityId = barcode,
                    payload = gson.toJson(mapOf(
                        "barcode" to barcode,
                        "offline_seq" to offlineSeqCounter,
                        "client_ts" to System.currentTimeMillis()
                    ))
                )
                _uiState.update { it.copy(isProcessing = false) }
                _events.emit(CourierEvent.ShowMessage(ua.com.programmer.pick.R.string.box_picked_up))
            }
        }
    }

    fun confirmDelivery(barcode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            offlineSeqCounter++

            val message = SyncMessage.BoxDeliveryConfirm(
                id = messageParser.generateMessageId(),
                timestamp = messageParser.getCurrentTimestamp(),
                barcode = barcode,
                offlineSeq = offlineSeqCounter,
                clientTs = System.currentTimeMillis()
            )

            if (webSocketManager.isConnected()) {
                val response = webSocketManager.sendAndAwait(
                    message,
                    SyncMessage.BoxDeliveryConfirmResult::class.java
                )

                _uiState.update { it.copy(isProcessing = false) }

                if (response?.success == true) {
                    _events.emit(CourierEvent.ShowMessage(ua.com.programmer.pick.R.string.box_delivered))
                    checkAllConfirmed()
                } else {
                    _events.emit(CourierEvent.ShowMessage(ua.com.programmer.pick.R.string.delivery_confirm_error))
                }
            } else {
                outgoingOperationRepository.queueOperation(
                    operationType = OperationType.BOX_DELIVERY_CONFIRM,
                    entityType = EntityType.DOCUMENT_BOX,
                    entityId = barcode,
                    payload = gson.toJson(mapOf(
                        "barcode" to barcode,
                        "offline_seq" to offlineSeqCounter,
                        "client_ts" to System.currentTimeMillis()
                    ))
                )
                _uiState.update { it.copy(isProcessing = false) }
                _events.emit(CourierEvent.ShowMessage(ua.com.programmer.pick.R.string.box_delivered))
            }
        }
    }

    private suspend fun checkAllConfirmed() {
        val state = _uiState.value
        if (state.confirmedBoxes + 1 >= state.totalBoxes) {
            val resId = when (state.mode) {
                CourierMode.PICKUP -> ua.com.programmer.pick.R.string.all_boxes_picked_up
                CourierMode.DELIVERY -> ua.com.programmer.pick.R.string.all_boxes_delivered
            }
            _events.emit(CourierEvent.ShowMessage(resId))
            _events.emit(CourierEvent.AllBoxesConfirmed)
        }
    }
}
