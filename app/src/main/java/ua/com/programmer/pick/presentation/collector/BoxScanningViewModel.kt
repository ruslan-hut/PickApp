package ua.com.programmer.pick.presentation.collector

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.data.remote.websocket.MessageParser
import ua.com.programmer.pick.data.remote.websocket.SyncMessage
import ua.com.programmer.pick.data.remote.websocket.WebSocketManager
import ua.com.programmer.pick.domain.model.DocumentBox
import ua.com.programmer.pick.domain.repository.BoxRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

data class BoxScanningUiState(
    val documentId: String = "",
    val boxes: List<DocumentBox> = emptyList(),
    val scannedBarcode: String = "",
    val weight: String = "",
    val isScanning: Boolean = false,
    val errorMessage: String? = null
)

sealed class BoxScanningEvent {
    data class ShowMessage(val messageResId: Int) : BoxScanningEvent()
    data object NavigateBack : BoxScanningEvent()
}

@HiltViewModel
class BoxScanningViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boxRepository: BoxRepository,
    private val webSocketManager: WebSocketManager,
    private val messageParser: MessageParser,
    private val barcodeService: BarcodeService
) : ViewModel() {

    private val documentId: String = savedStateHandle.get<String>(Screen.DOCUMENT_ID_ARG) ?: ""

    private val _uiState = MutableStateFlow(BoxScanningUiState(documentId = documentId))
    val uiState: StateFlow<BoxScanningUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<BoxScanningEvent>()
    val events = _events.asSharedFlow()

    init {
        observeBoxes()
        observeScanner()
    }

    private fun observeBoxes() {
        viewModelScope.launch {
            boxRepository.getBoxesByDocumentId(documentId).collect { boxes ->
                _uiState.update { it.copy(boxes = boxes) }
            }
        }
    }

    private fun observeScanner() {
        viewModelScope.launch {
            barcodeService.scannedBarcodes.collect { scanned ->
                _uiState.update { it.copy(scannedBarcode = scanned.rawValue) }
            }
        }
    }

    fun onBarcodeChanged(barcode: String) {
        _uiState.update { it.copy(scannedBarcode = barcode) }
    }

    fun onWeightChanged(weight: String) {
        _uiState.update { it.copy(weight = weight) }
    }

    fun scanBox() {
        val state = _uiState.value
        val barcode = state.scannedBarcode.trim()
        val weight = state.weight.toIntOrNull() ?: 0

        if (barcode.isBlank() || weight <= 0) return

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, errorMessage = null) }

            val message = SyncMessage.BoxScan(
                id = messageParser.generateMessageId(),
                timestamp = messageParser.getCurrentTimestamp(),
                documentId = documentId,
                barcode = barcode,
                weight = weight
            )

            val response = webSocketManager.sendAndAwait(
                message,
                SyncMessage.BoxScanResult::class.java
            )

            if (response?.success == true) {
                _uiState.update {
                    it.copy(
                        scannedBarcode = "",
                        weight = "",
                        isScanning = false
                    )
                }
                _events.emit(BoxScanningEvent.ShowMessage(ua.com.programmer.pick.R.string.box_added))
            } else {
                val errorResId = if (response?.error?.contains("not found", ignoreCase = true) == true) {
                    ua.com.programmer.pick.R.string.box_not_found
                } else {
                    ua.com.programmer.pick.R.string.box_scan_error
                }
                _uiState.update { it.copy(isScanning = false) }
                _events.emit(BoxScanningEvent.ShowMessage(errorResId))
            }
        }
    }
}
