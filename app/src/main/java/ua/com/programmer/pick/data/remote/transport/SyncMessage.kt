package ua.com.programmer.pick.data.remote.transport

import com.google.gson.JsonElement

/**
 * Types of messages in sync protocol
 */
enum class MessageType {
    // Heartbeat
    PING,
    PONG,

    // User authentication (after transport connection)
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
    DOCUMENT_UPDATE_RESULT,

    // Stage operations (generalized lock/unlock/pause/complete for any stage)
    STAGE_LOCK,
    STAGE_UNLOCK,
    STAGE_PAUSE,
    STAGE_COMPLETE,
    STAGE_LOCK_RESULT,
    STAGE_COMPLETE_RESULT,

    // Pack-stage box operations (worker adds/removes boxes on a document in PACKING state)
    BOX_ADD,
    BOX_ADD_RESULT,
    BOX_REMOVE,
    BOX_REMOVE_RESULT,
    // Client fallback when a scanned barcode is not in the local catalog
    BOX_LOOKUP,
    BOX_LOOKUP_RESULT,

    // Courier box operations
    BOX_PICKUP_CONFIRM,
    BOX_PICKUP_CONFIRM_RESULT,
    BOX_DELIVERY_CONFIRM,
    BOX_DELIVERY_CONFIRM_RESULT,

    // Product lookup
    PRODUCT_LOOKUP,
    PRODUCT_LOOKUP_RESULT,

    // Per-line photo: request a signed upload URL, then HTTP POST the bytes
    LINE_PHOTO_UPLOAD_URL,
    LINE_PHOTO_UPLOAD_URL_RESULT,

    // Targeted refresh
    DOCUMENT_LIST_REFRESH,
    DOCUMENT_PRODUCTS,

    // Errors and notifications
    ERROR_REPORT,
    SERVER_ERROR,
    PUSH,

    // Admin / system-initiated cooperative force-release. Server -> device
    // request to drop dirty edits and send STAGE_UNLOCK; the admin's HTTP
    // request completes when the unlock lands within 10s.
    FORCE_RELEASE_REQUEST,

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
     * Server keep-alive pong response. Carries the per-device debug-journal
     * flag so admin-side toggles propagate within a ping interval (~30s)
     * without requiring re-login. Null = the server didn't include the field
     * (older server build); leave the local flag as-is.
     */
    data class Pong(
        override val id: String,
        override val timestamp: String,
        val debugJournalEnabled: Boolean? = null
    ) : SyncMessage() {
        override val type = MessageType.PONG
    }

    // ============================================
    // User Authentication Messages
    // ============================================

    /**
     * Client request to authenticate user after transport connection
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
        // ERP external_ids of documents this (user, device) pair already
        // holds an in-process stage lock for. Populated by the v2 backend
        // so the orchestrator can rebuild its session-scoped
        // `heldStageLocks` set immediately on connect — closing the
        // post-restart / post-reconnect race where inbound SYNC_DATA
        // could overwrite worker-owned line data before the user thinks
        // to re-open the doc. Null/empty on a fresh login.
        val heldStageLocks: List<String>? = null,
        // True when the backend confirms DOCUMENT_UPDATE writes with a
        // DOCUMENT_UPDATE_RESULT frame. The orchestrator gates its
        // clear-dirty-only-on-ack behaviour on this so an updated client
        // talking to an old server falls back to the legacy buffer-accept
        // clear instead of waiting out an ack that never arrives.
        val supportsUpdateAck: Boolean = false,
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
     *
     * visibleIds is the delta counterpart of fullSet: the complete set of ids
     * of this entity type the server still considers in scope, independent of
     * what `data` carries. null = no purge information (keep everything);
     * an empty list = purge everything. It is what lets the device drop a
     * document that silently left its scope — a queue-head preview another
     * worker took, a stage that advanced, an ERP ack — which a pure delta
     * never mentions again.
     */
    data class SyncData(
        override val id: String,
        override val timestamp: String,
        val entityType: String,
        val data: JsonElement,
        val deletedIds: List<String>? = null,
        val fullSet: Boolean = false,
        val visibleIds: List<String>? = null
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

    /**
     * Server confirmation for a DOCUMENT_UPDATE. requestId echoes the id of the
     * DocumentUpdate it confirms (document_id alone can't disambiguate
     * concurrent updates on the same doc). On success the orchestrator clears
     * is_dirty for the confirmed lines and adopts version; on failure it leaves
     * them dirty for the next resync.
     * Payload: success, document_id, request_id, version, error_code
     */
    data class DocumentUpdateResult(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val requestId: String,
        val success: Boolean,
        val version: Long? = null,
        val errorCode: String? = null
    ) : SyncMessage() {
        override val type = MessageType.DOCUMENT_UPDATE_RESULT
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
     * Client request to pause work on a stage: releases the lock without
     * reverting the document state. Document stays at the in-process state
     * (COLLECTING / PACKING) in the worker's queue. The active segment elapsed
     * since the lock was taken is added to the document's active_work_ms by
     * the server, so the paused interval is excluded from the worker's
     * effective work time. Resume is the regular STAGE_LOCK on the same doc.
     * Payload: document_id, stage. Response is a STAGE_LOCK_RESULT.
     */
    data class StagePause(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val stage: String
    ) : SyncMessage() {
        override val type = MessageType.STAGE_PAUSE
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
    // Per-line Photo Messages
    // ============================================

    /**
     * Client request for a short-lived signed URL to upload a line's photo.
     * Correlated by document_id + line_number (multiple lines may be in flight).
     */
    data class LinePhotoUploadUrl(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val lineNumber: Int
    ) : SyncMessage() {
        override val type = MessageType.LINE_PHOTO_UPLOAD_URL
    }

    /**
     * Server response carrying the signed upload URL and its expiry. The URL is
     * absolute and self-authenticating (token in the query) — POST raw JPEG
     * bytes to it directly, do not prefix BASE_URL or attach a bearer.
     */
    data class LinePhotoUploadUrlResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val documentId: String? = null,
        val lineNumber: Int? = null,
        val uploadUrl: String? = null,
        val expiresAt: Long? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.LINE_PHOTO_UPLOAD_URL_RESULT
    }

    // ============================================
    // Pack-stage Box Messages
    // ============================================

    /**
     * Worker scans a box barcode during the PACK stage to link it to a document.
     * Parcel boxes require weight > 0; for packages the server forces weight = 0.
     */
    data class BoxAdd(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val barcode: String,
        val weight: Int
    ) : SyncMessage() {
        override val type = MessageType.BOX_ADD
    }

    /**
     * Server response for BOX_ADD. On success, `box` carries the full DocumentBox
     * DTO so the client can render it immediately without a round-trip sync.
     */
    data class BoxAddResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val box: JsonElement? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.BOX_ADD_RESULT
    }

    /**
     * Worker removes a previously-added box while the document is still in PACKING.
     * boxNumber is the in-document sequence assigned by the server on BOX_ADD.
     */
    data class BoxRemove(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
        val boxNumber: Int
    ) : SyncMessage() {
        override val type = MessageType.BOX_REMOVE
    }

    data class BoxRemoveResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val boxNumber: Int? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.BOX_REMOVE_RESULT
    }

    /**
     * Client fallback when a scanned barcode misses the local box catalog.
     * The server resolves it against the master catalog and, on a hit, returns
     * the full BoxDto so the client can cache it and continue the add flow.
     */
    data class BoxLookup(
        override val id: String,
        override val timestamp: String,
        val barcode: String
    ) : SyncMessage() {
        override val type = MessageType.BOX_LOOKUP
    }

    data class BoxLookupResult(
        override val id: String,
        override val timestamp: String,
        val success: Boolean,
        val box: JsonElement? = null,
        val error: String? = null
    ) : SyncMessage() {
        override val type = MessageType.BOX_LOOKUP_RESULT
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
     * Payload: code, message, details, optional document_id
     *
     * `documentId` is populated for write-path rejections (codes
     * `LOCK_LOST`, `WRONG_STATE`) so the orchestrator can drive M5″
     * lock-loss recovery for the specific document without parsing the
     * human-readable message. Empty for connection-level errors
     * (`NOT_AUTHENTICATED`, `INVALID_PAYLOAD`, …).
     */
    data class ServerError(
        override val id: String,
        override val timestamp: String,
        val code: String,
        val message: String,
        val details: String? = null,
        val documentId: String? = null
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

    /**
     * Server-initiated request to release the worker's stage lock
     * cooperatively. Triggered by an admin clicking "Force release" in
     * the tenant UI while the device is connected. The orchestrator
     * runs the equivalent of "exit without saving":
     *   1. Cancel pending debounced sync for this doc.
     *   2. Drop any locally-dirty edits (zero actuals/is_completed/batch).
     *   3. Send STAGE_UNLOCK so the server completes the cooperative flow.
     *   4. Emit a UI event so any open detail screen surfaces a banner
     *      and navigates back.
     * documentId is the ERP external_id.
     */
    data class ForceReleaseRequest(
        override val id: String,
        override val timestamp: String,
        val documentId: String,
    ) : SyncMessage() {
        override val type = MessageType.FORCE_RELEASE_REQUEST
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
    val isCompleted: Boolean = false,
    // Worker-owned line note. Sent full-state on every line update: the server
    // overwrites its copy with whatever arrives, so an omitted/empty note clears
    // it. Always carry the line's current note (see MessageParser serialization).
    val notes: String? = null
)

/**
 * Per-type capability flags sent by the server. Null means "not specified by
 * the ERP" — the client applies its own default (see [AvailableDocumentType]).
 */
data class AvailableDocumentTypeDto(
    val code: String,
    val description: String,
    val allowsOverPlan: Boolean? = null,
    val allowsExtraLines: Boolean? = null,
    val requiresPlan: Boolean? = null
)
