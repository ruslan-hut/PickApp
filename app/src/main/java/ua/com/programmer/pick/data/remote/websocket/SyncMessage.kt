package ua.com.programmer.pick.data.remote.websocket

import com.google.gson.JsonElement

/**
 * Types of messages in WebSocket protocol
 */
enum class MessageType {
    // Heartbeat
    PING,
    PONG,

    // Synchronization
    SYNC_REQUEST,
    FULL_SYNC_REQUEST,
    SYNC_DATA,
    SYNC_COMPLETE,
    ACK,

    // Document operations
    DOCUMENT_LOCK,
    DOCUMENT_UNLOCK,
    DOCUMENT_UPDATE,
    DOCUMENT_COMPLETE,
    DOCUMENT_LOCK_RESULT,
    DOCUMENT_COMPLETE_RESULT,

    // Product lookup
    PRODUCT_LOOKUP,
    PRODUCT_LOOKUP_RESULT,

    // Errors and notifications
    ERROR_REPORT,
    SERVER_ERROR,
    PUSH
}

/**
 * Base sealed class for all sync messages.
 * All messages follow the envelope format: id, type, timestamp, payload
 */
sealed class SyncMessage {
    abstract val id: String
    abstract val type: MessageType
    abstract val timestamp: String  // ISO 8601 format

    // ============================================
    // Heartbeat Messages
    // ============================================

    /**
     * Client keep-alive ping
     */
    data class Ping(
        override val id: String,
        override val timestamp: String
    ) : SyncMessage() {
        override val type = MessageType.PING
    }

    /**
     * Server keep-alive pong response
     */
    data class Pong(
        override val id: String,
        override val timestamp: String
    ) : SyncMessage() {
        override val type = MessageType.PONG
    }

    // ============================================
    // Synchronization Messages
    // ============================================

    /**
     * Client request for delta sync
     * Payload: entity_types, cursors (optional)
     */
    data class SyncRequest(
        override val id: String,
        override val timestamp: String,
        val entityTypes: List<String>,
        val cursors: Map<String, String>? = null  // entity_type -> ISO 8601 timestamp
    ) : SyncMessage() {
        override val type = MessageType.SYNC_REQUEST
    }

    /**
     * Client request for full sync
     * Payload: entity_types
     */
    data class FullSyncRequest(
        override val id: String,
        override val timestamp: String,
        val entityTypes: List<String>
    ) : SyncMessage() {
        override val type = MessageType.FULL_SYNC_REQUEST
    }

    /**
     * Server sync data batch
     * Payload: entity_type, data, deleted_ids
     */
    data class SyncData(
        override val id: String,
        override val timestamp: String,
        val entityType: String,
        val data: JsonElement,
        val deletedIds: List<String>? = null
    ) : SyncMessage() {
        override val type = MessageType.SYNC_DATA
    }

    /**
     * Server notification that sync is complete
     * Payload: sync_id, cursors
     */
    data class SyncComplete(
        override val id: String,
        override val timestamp: String,
        val syncId: String,
        val cursors: Map<String, String>  // entity_type -> ISO 8601 timestamp
    ) : SyncMessage() {
        override val type = MessageType.SYNC_COMPLETE
    }

    /**
     * Client acknowledgment of sync data
     * Payload: sync_id, cursors
     */
    data class Ack(
        override val id: String,
        override val timestamp: String,
        val syncId: String,
        val cursors: Map<String, String>
    ) : SyncMessage() {
        override val type = MessageType.ACK
    }

    // ============================================
    // Document Operation Messages
    // ============================================

    /**
     * Client request to lock document ("Take into work")
     * Payload: document_id
     */
    data class DocumentLock(
        override val id: String,
        override val timestamp: String,
        val documentId: String
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_LOCK
    }

    /**
     * Client request to unlock document
     * Payload: document_id
     */
    data class DocumentUnlock(
        override val id: String,
        override val timestamp: String,
        val documentId: String
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_UNLOCK
    }

    /**
     * Client notification about document/line updates
     * Payload: document_id, state, lines
     */
    data class DocumentUpdate(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val state: String,
        val lines: List<DocumentLineUpdate>
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_UPDATE
    }

    /**
     * Client request to complete document
     * Payload: document_id
     */
    data class DocumentComplete(
        override val id: String,
        override val timestamp: String,
        val documentId: String
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_COMPLETE
    }

    /**
     * Server response for document lock operation
     * Payload: document_id, success, locked_by, error
     */
    data class DocumentLockResult(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val success: Boolean,
        val lockedBy: String? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_LOCK_RESULT
    }

    /**
     * Server response for document complete operation
     * Payload: document_id, success, state, completed_at, version, error
     */
    data class DocumentCompleteResult(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val success: Boolean,
        val state: String? = null,
        val completedAt: String? = null,
        val version: Int? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_COMPLETE_RESULT
    }

    // ============================================
    // Product Lookup Messages
    // ============================================

    /**
     * Client request to lookup product by barcode
     * Payload: barcode
     */
    data class ProductLookup(
        override val id: String,
        override val timestamp: String,
        val barcode: String
    ) : SyncMessage() {
        override val type = MessageType.PRODUCT_LOOKUP
    }

    /**
     * Server response for product lookup
     * Payload: success, product, error
     */
    data class ProductLookupResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val product: JsonElement? = null,  // ProductDto as JSON
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.PRODUCT_LOOKUP_RESULT
    }

    // ============================================
    // Error and Notification Messages
    // ============================================

    /**
     * Client error report
     * Payload: error_type, message, stack_trace, metadata
     */
    data class ErrorReport(
        override val id: String,
        override val timestamp: String,
        val errorType: String,
        val message: String,
        val stackTrace: String? = null,
        val metadata: Map<String, String>? = null
    ) : SyncMessage() {
        override val type = MessageType.ERROR_REPORT
    }

    /**
     * Server error response
     * Payload: code, message, details
     */
    data class ServerError(
        override val id: String,
        override val timestamp: String,
        val code: String,
        val message: String,
        val details: String? = null
    ) : SyncMessage() {
        override val type = MessageType.SERVER_ERROR
    }

    /**
     * Server push notification
     * Payload: event, entity_type, entity_id, data
     */
    data class Push(
        override val id: String,
        override val timestamp: String,
        val event: String,
        val entityType: String? = null,
        val entityId: String? = null,
        val data: JsonElement? = null
    ) : SyncMessage() {
        override val type = MessageType.PUSH
    }
}

/**
 * Line update data for DOCUMENT_UPDATE message
 */
data class DocumentLineUpdate(
    val lineNumber: Int,
    val actualQuantity: Double,
    val batchNumber: String? = null,
    val isCompleted: Boolean = false
)

/**
 * Operation types for outgoing queue
 */
enum class OperationType {
    DOCUMENT_LOCK,
    DOCUMENT_UNLOCK,
    DOCUMENT_UPDATE,
    DOCUMENT_COMPLETE,
    SYNC_REQUEST,
    PRODUCT_LOOKUP
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
