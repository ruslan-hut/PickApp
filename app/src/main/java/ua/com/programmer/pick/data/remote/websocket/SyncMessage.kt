package ua.com.programmer.pick.data.remote.websocket

import com.google.gson.JsonElement

/**
 * Types of messages in WebSocket protocol
 */
enum class MessageType {
    // Heartbeat
    PING,
    PONG,

    // User authentication (after WebSocket connection)
    USER_LOGIN,
    USER_LOGIN_RESULT,

    // Synchronization
    SYNC_REQUEST,
    FULL_SYNC_REQUEST,
    SYNC_DATA,
    SYNC_COMPLETE,
    ACK,

    // Document operations
    DOCUMENT_UPDATE,

    // Stage operations (generalized lock/unlock/complete for any stage)
    STAGE_LOCK,
    STAGE_UNLOCK,
    STAGE_COMPLETE,
    STAGE_LOCK_RESULT,
    STAGE_COMPLETE_RESULT,

    // Box scanning
    BOX_SCAN,
    BOX_SCAN_RESULT,

    // Courier box operations
    BOX_PICKUP_CONFIRM,
    BOX_PICKUP_CONFIRM_RESULT,
    BOX_DELIVERY_CONFIRM,
    BOX_DELIVERY_CONFIRM_RESULT,

    // Product lookup
    PRODUCT_LOOKUP,
    PRODUCT_LOOKUP_RESULT,

    // Targeted refresh
    DOCUMENT_LIST_REFRESH,
    DOCUMENT_PRODUCTS,

    // Errors and notifications
    ERROR_REPORT,
    SERVER_ERROR,
    PUSH,

    // Debug journal
    DEBUG_EVENT_BATCH,
    DEBUG_EVENT_BATCH_RESULT
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
    // User Authentication Messages
    // ============================================

    /**
     * Client request to authenticate user after WebSocket connection
     * Payload: login, password
     */
    data class UserLogin(
        override val id: String,
        override val timestamp: String,
        val login: String,
        val password: String
    ) : SyncMessage() {
        override val type = MessageType.USER_LOGIN
    }

    /**
     * Server response for user login
     * Payload: success, user_id, user_name, role, offline_hash, error_message
     */
    data class UserLoginResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val userId: String? = null,
        // ERP external_id of the authenticated worker. Sent by v2 backend so
        // the app can populate UserEntity.externalId immediately on login,
        // before the full users sync runs.
        val userExternalId: String? = null,
        val userName: String? = null,
        val role: String? = null,
        val offlineHash: String? = null,
        val tenantId: String? = null,
        val availableDocumentTypes: List<AvailableDocumentTypeDto>? = null,
        val debugJournalEnabled: Boolean? = null,
        val errorMessage: String? = null
    ) : SyncMessage() {
        override val type = MessageType.USER_LOGIN_RESULT
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
     * Server sync data batch.
     *
     * fullSet distinguishes a delta sync (false — merge-only; local rows
     * outside this payload must NOT be purged) from a full list refresh
     * (true — authoritative set; local rows outside this payload must be
     * deleted so the UI reflects server-side removals). Absent = false
     * (safer default). See the server's SyncDataPayload doc for the full
     * invariant.
     */
    data class SyncData(
        override val id: String,
        override val timestamp: String,
        val entityType: String,
        val data: JsonElement,
        val deletedIds: List<String>? = null,
        val fullSet: Boolean = false
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
    // Targeted Refresh Messages
    // ============================================

    /**
     * Client request to refresh document list with related warehouses and clients
     */
    data class DocumentListRefresh(
        override val id: String,
        override val timestamp: String,
        val documentType: String? = null
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_LIST_REFRESH
    }

    /**
     * Client request for products referenced by a specific document
     * Payload: document_id
     */
    data class DocumentProducts(
        override val id: String,
        override val timestamp: String,
        val documentId: String
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_PRODUCTS
    }

    // ============================================
    // Document Operation Messages
    // ============================================

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

    // ============================================
    // Stage Operation Messages
    // ============================================

    /**
     * Client request to lock document for a stage
     * Payload: document_id, stage ("collect"/"pack"/"deliver")
     */
    data class StageLock(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val stage: String
    ) : SyncMessage() {
        override val type = MessageType.STAGE_LOCK
    }

    /**
     * Client request to unlock document from a stage
     * Payload: document_id, stage
     */
    data class StageUnlock(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val stage: String
    ) : SyncMessage() {
        override val type = MessageType.STAGE_UNLOCK
    }

    /**
     * Client request to complete current stage
     * Payload: document_id, stage
     */
    data class StageComplete(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val stage: String
    ) : SyncMessage() {
        override val type = MessageType.STAGE_COMPLETE
    }

    /**
     * Server response for stage lock operation
     * Payload: document_id, stage, success, locked_by, error
     */
    data class StageLockResult(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val stage: String,
        val success: Boolean,
        val lockedBy: String? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.STAGE_LOCK_RESULT
    }

    /**
     * Server response for stage complete operation
     * Payload: document_id, stage, success, state, completed_at, version, error
     */
    data class StageCompleteResult(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val stage: String,
        val success: Boolean,
        val state: String? = null,
        val completedAt: String? = null,
        val version: Long? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.STAGE_COMPLETE_RESULT
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
    // Box Scanning Messages
    // ============================================

    /**
     * Client scans a box barcode during collection
     */
    data class BoxScan(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val barcode: String,
        val weight: Int
    ) : SyncMessage() {
        override val type = MessageType.BOX_SCAN
    }

    /**
     * Server response for box scan
     */
    data class BoxScanResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val boxId: String? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.BOX_SCAN_RESULT
    }

    // ============================================
    // Courier Box Operation Messages
    // ============================================

    /**
     * Client confirms box pickup (offline-capable)
     */
    data class BoxPickupConfirm(
        override val id: String,
        override val timestamp: String,
        val barcode: String,
        val offlineSeq: Int,
        val clientTs: Long
    ) : SyncMessage() {
        override val type = MessageType.BOX_PICKUP_CONFIRM
    }

    /**
     * Server response for box pickup confirm
     */
    data class BoxPickupConfirmResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val barcode: String? = null,
        val wasNoop: Boolean = false
    ) : SyncMessage() {
        override val type = MessageType.BOX_PICKUP_CONFIRM_RESULT
    }

    /**
     * Client confirms box delivery (offline-capable)
     */
    data class BoxDeliveryConfirm(
        override val id: String,
        override val timestamp: String,
        val barcode: String,
        val offlineSeq: Int,
        val clientTs: Long
    ) : SyncMessage() {
        override val type = MessageType.BOX_DELIVERY_CONFIRM
    }

    /**
     * Server response for box delivery confirm
     */
    data class BoxDeliveryConfirmResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val barcode: String? = null,
        val wasNoop: Boolean = false
    ) : SyncMessage() {
        override val type = MessageType.BOX_DELIVERY_CONFIRM_RESULT
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

    // ============================================
    // Debug Journal Messages
    // ============================================

    /**
     * Client batch upload of debug journal events. Best-effort.
     */
    data class DebugEventBatch(
        override val id: String,
        override val timestamp: String,
        val tenantId: String,
        val deviceId: String,
        val events: List<DebugEventPayload>
    ) : SyncMessage() {
        override val type = MessageType.DEBUG_EVENT_BATCH
    }

    /**
     * Server ack for debug event batch. `acceptedIds` lists which events
     * were persisted; the client marks them uploaded.
     */
    data class DebugEventBatchResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val acceptedIds: List<String>,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.DEBUG_EVENT_BATCH_RESULT
    }
}

/**
 * Single debug event wire payload (mirrors DebugJournalEntity minus upload state).
 */
data class DebugEventPayload(
    val id: String,
    val userId: String?,
    val documentId: String?,
    val stage: String?,
    val eventType: String,
    val severity: String,
    val message: String,
    val payloadJson: String?,
    val createdAt: Long
)

/**
 * Line update data for DOCUMENT_UPDATE message
 */
data class DocumentLineUpdate(
    val lineNumber: Int,
    val actualQuantity: Double,
    val batchNumber: String? = null,
    val isCompleted: Boolean = false
)

data class AvailableDocumentTypeDto(
    val code: String,
    val description: String
)
