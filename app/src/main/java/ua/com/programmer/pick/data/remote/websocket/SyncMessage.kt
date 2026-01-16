package ua.com.programmer.pick.data.remote.websocket

import com.google.gson.JsonElement

/**
 * Types of messages in WebSocket protocol
 */
enum class MessageType {
    // Server -> Client
    DELTA_UPDATE,
    DOCUMENT_LOCK,
    ACK,
    ERROR,
    CONNECTED,

    // Client -> Server
    TAKE_INTO_WORK,
    DOCUMENT_UPDATE,
    LINE_UPDATE,
    COMPLETE_DOCUMENT,
    SUBSCRIBE,
    UNSUBSCRIBE
}

/**
 * Base sealed class for all sync messages
 */
sealed class SyncMessage {
    abstract val type: MessageType
    abstract val messageId: String

    /**
     * Server notification about data updates
     */
    data class DeltaUpdate(
        override val messageId: String,
        val entityType: String,
        val timestamp: Long,
        val isFullSync: Boolean,
        val data: JsonElement,
        val deletedIds: List<String>? = null
    ) : SyncMessage() {
        override val type = MessageType.DELTA_UPDATE
    }

    /**
     * Server notification that a document was locked by another user
     */
    data class DocumentLock(
        override val messageId: String,
        val documentId: String,
        val lockedBy: String,
        val lockedByName: String,
        val lockedAt: Long
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_LOCK
    }

    /**
     * Server acknowledgment of client operation
     */
    data class Acknowledgment(
        override val messageId: String,
        val originalMessageId: String,
        val success: Boolean,
        val newVersion: Int? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.ACK
    }

    /**
     * Server error message
     */
    data class ServerError(
        override val messageId: String,
        val code: String,
        val message: String,
        val relatedMessageId: String? = null
    ) : SyncMessage() {
        override val type = MessageType.ERROR
    }

    /**
     * Server connection confirmation
     */
    data class Connected(
        override val messageId: String,
        val serverTime: Long,
        val sessionId: String
    ) : SyncMessage() {
        override val type = MessageType.CONNECTED
    }

    /**
     * Client request to take document into work
     */
    data class TakeIntoWork(
        override val messageId: String,
        val documentId: String,
        val userId: String,
        val timestamp: Long
    ) : SyncMessage() {
        override val type = MessageType.TAKE_INTO_WORK
    }

    /**
     * Client notification about document header update
     */
    data class DocumentUpdate(
        override val messageId: String,
        val documentId: String,
        val state: String,
        val notes: String?,
        val totalActual: Double,
        val version: Int,
        val timestamp: Long
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_UPDATE
    }

    /**
     * Client notification about line update
     */
    data class LineUpdate(
        override val messageId: String,
        val documentId: String,
        val lineId: String,
        val actualQuantity: Double,
        val batchNumber: String?,
        val locationId: String?,
        val notes: String?,
        val isCompleted: Boolean,
        val timestamp: Long
    ) : SyncMessage() {
        override val type = MessageType.LINE_UPDATE
    }

    /**
     * Client notification about document completion
     */
    data class CompleteDocument(
        override val messageId: String,
        val documentId: String,
        val userId: String,
        val completedAt: Long,
        val version: Int
    ) : SyncMessage() {
        override val type = MessageType.COMPLETE_DOCUMENT
    }

    /**
     * Client subscription to entity updates
     */
    data class Subscribe(
        override val messageId: String,
        val entityTypes: List<String>
    ) : SyncMessage() {
        override val type = MessageType.SUBSCRIBE
    }

    /**
     * Client unsubscription from entity updates
     */
    data class Unsubscribe(
        override val messageId: String,
        val entityTypes: List<String>
    ) : SyncMessage() {
        override val type = MessageType.UNSUBSCRIBE
    }
}

/**
 * Operation types for outgoing queue
 */
enum class OperationType {
    TAKE_INTO_WORK,
    UPDATE_DOCUMENT,
    UPDATE_LINE,
    COMPLETE_DOCUMENT,
    SYNC_REQUEST
}

/**
 * Status of outgoing operations
 */
enum class OperationStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    COMPLETED,
    FAILED
}
