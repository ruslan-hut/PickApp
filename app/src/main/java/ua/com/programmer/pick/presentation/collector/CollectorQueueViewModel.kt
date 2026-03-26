package ua.com.programmer.pick.presentation.collector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.data.mapper.DocumentMapper
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.remote.dto.DocumentDto
import ua.com.programmer.pick.data.remote.websocket.MessageParser
import ua.com.programmer.pick.data.remote.websocket.SyncMessage
import ua.com.programmer.pick.data.remote.websocket.WebSocketManager
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

data class CollectorQueueUiState(
    val currentDocument: Document? = null,
    val isRequesting: Boolean = false,
    val isQueueEmpty: Boolean = false,
    val errorMessage: String? = null
)

sealed class CollectorQueueEvent {
    data class NavigateToDocument(val route: String) : CollectorQueueEvent()
    data class ShowMessage(val message: String) : CollectorQueueEvent()
}

@HiltViewModel
class CollectorQueueViewModel @Inject constructor(
    private val webSocketManager: WebSocketManager,
    private val messageParser: MessageParser,
    private val documentRepository: DocumentRepository,
    private val documentMapper: DocumentMapper,
    private val syncOrchestrator: SyncOrchestrator,
    private val gson: Gson
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectorQueueUiState())
    val uiState: StateFlow<CollectorQueueUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CollectorQueueEvent>()
    val events = _events.asSharedFlow()

    fun requestNextDocument() {
        if (_uiState.value.isRequesting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRequesting = true, isQueueEmpty = false, errorMessage = null) }

            val message = SyncMessage.NextDocumentRequest(
                id = messageParser.generateMessageId(),
                timestamp = messageParser.getCurrentTimestamp()
            )

            val response = webSocketManager.sendAndAwait(
                message,
                SyncMessage.NextDocumentResult::class.java
            )

            if (response != null) {
                if (response.success && response.document != null) {
                    try {
                        val dto: DocumentDto = gson.fromJson(response.document, DocumentDto::class.java)
                        val entity = documentMapper.toEntity(dto)
                        // Save to local DB
                        documentRepository.saveDocument(entity.toDomain())
                        // Save lines if present
                        dto.lines?.let { lines ->
                            val lineEntities = lines.map { documentMapper.toLineEntity(it) }
                            documentRepository.saveLines(lineEntities.map { it.toDomain() })
                        }

                        _uiState.update {
                            it.copy(
                                currentDocument = entity.toDomain(),
                                isRequesting = false
                            )
                        }
                        _events.emit(CollectorQueueEvent.NavigateToDocument(
                            Screen.DocumentDetail.createRoute(dto.id)
                        ))
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(isRequesting = false, errorMessage = e.message)
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isRequesting = false,
                            isQueueEmpty = response.error?.contains("empty", ignoreCase = true) == true,
                            errorMessage = if (response.error?.contains("empty", ignoreCase = true) != true) response.error else null
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(isRequesting = false, errorMessage = "Request timeout")
                }
            }
        }
    }

    fun completeCollection(documentId: String) {
        viewModelScope.launch {
            val message = SyncMessage.CollectionComplete(
                id = messageParser.generateMessageId(),
                timestamp = messageParser.getCurrentTimestamp(),
                documentId = documentId
            )

            // Uses existing DOCUMENT_COMPLETE_RESULT format per backend spec
            val response = webSocketManager.sendAndAwait(
                message,
                SyncMessage.DocumentCompleteResult::class.java
            )

            if (response?.success == true) {
                _uiState.update { it.copy(currentDocument = null) }
                _events.emit(CollectorQueueEvent.ShowMessage("collection_complete"))
            } else {
                _events.emit(CollectorQueueEvent.ShowMessage(response?.error ?: "Complete failed"))
            }
        }
    }
}
