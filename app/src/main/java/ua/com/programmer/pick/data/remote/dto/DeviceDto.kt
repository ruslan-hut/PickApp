package ua.com.programmer.pick.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * Wire DTOs for the device REST transport (the backend's `/device` surface, the
 * REST replacement for the WebSocket protocol). Field shapes mirror the Go
 * `ws/protocol` payload structs the backend reuses, so a value parsed here is
 * byte-for-byte what the WebSocket path delivered — only the framing differs.
 *
 * Every endpoint wraps its result in [ApiEnvelope]: `{"status":"ok","data":…}`
 * on success, `{"status":"error","error":{code,message}}` on failure.
 */

/** Standard backend response envelope. */
data class ApiEnvelope<T>(
    @SerializedName("status") val status: String,
    @SerializedName("data") val data: T? = null,
    @SerializedName("error") val error: ApiError? = null,
) {
    val isOk: Boolean get() = status == "ok"
}

data class ApiError(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
)

/** Namespaces the device request/response bodies. */
object DeviceDto {

    // --- Auth ---

    data class LoginRequest(
        @SerializedName("app_token") val appToken: String,
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("login") val login: String,
        @SerializedName("password") val password: String,
        @SerializedName("app_version") val appVersion: String,
    )

    data class LoginResponse(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("refresh_token") val refreshToken: String,
        @SerializedName("expires_at") val expiresAt: Long,
        @SerializedName("user_id") val userId: String,
        @SerializedName("user_external_id") val userExternalId: String,
        @SerializedName("user_name") val userName: String,
        @SerializedName("role") val role: String,
        @SerializedName("offline_hash") val offlineHash: String,
        @SerializedName("tenant_id") val tenantId: String,
        @SerializedName("available_document_types") val availableDocumentTypes: List<AvailableDocumentType>? = null,
        @SerializedName("debug_journal_enabled") val debugJournalEnabled: Boolean = false,
        @SerializedName("held_stage_locks") val heldStageLocks: List<String>? = null,
    )

    data class AvailableDocumentType(
        @SerializedName("code") val code: String,
        @SerializedName("description") val description: String,
        @SerializedName("allows_over_plan") val allowsOverPlan: Boolean? = null,
        @SerializedName("allows_extra_lines") val allowsExtraLines: Boolean? = null,
        @SerializedName("requires_plan") val requiresPlan: Boolean? = null,
    )

    data class RefreshRequest(
        @SerializedName("refresh_token") val refreshToken: String,
    )

    data class RefreshResponse(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("refresh_token") val refreshToken: String,
        @SerializedName("expires_at") val expiresAt: Long,
    )

    // --- Sync ---

    data class SyncRequest(
        @SerializedName("entity_types") val entityTypes: List<String>,
        @SerializedName("applied_cursors") val appliedCursors: Map<String, String>? = null,
    )

    data class SyncResponse(
        @SerializedName("entities") val entities: List<SyncEntity>? = null,
        @SerializedName("next_cursors") val nextCursors: Map<String, String>? = null,
        @SerializedName("has_more") val hasMore: Boolean = false,
    )

    /** One entity batch. `data` is the raw entity-row array (same shape the WS
     *  SYNC_DATA frame carried); the existing per-entity mappers parse it. */
    data class SyncEntity(
        @SerializedName("entity_type") val entityType: String,
        @SerializedName("data") val data: JsonElement? = null,
        @SerializedName("deleted_ids") val deletedIds: List<String>? = null,
        @SerializedName("full_set") val fullSet: Boolean = false,
    )

    // --- Stage operations ---

    data class StageRequest(
        @SerializedName("stage") val stage: String,
    )

    data class StageLockResult(
        @SerializedName("document_id") val documentId: String,
        @SerializedName("stage") val stage: String,
        @SerializedName("success") val success: Boolean,
        @SerializedName("locked_by") val lockedBy: String? = null,
        @SerializedName("error") val error: String? = null,
    )

    data class StageCompleteResult(
        @SerializedName("document_id") val documentId: String,
        @SerializedName("stage") val stage: String,
        @SerializedName("success") val success: Boolean,
        @SerializedName("state") val state: String? = null,
        @SerializedName("completed_at") val completedAt: String? = null,
        @SerializedName("version") val version: Long = 0,
        @SerializedName("error") val error: String? = null,
    )

    // --- Document line write ---

    data class UpdateRequest(
        @SerializedName("lines") val lines: List<DocumentLineUpdate>,
    )

    data class DocumentLineUpdate(
        @SerializedName("line_number") val lineNumber: Int,
        @SerializedName("actual_quantity") val actualQuantity: Double,
        @SerializedName("batch_number") val batchNumber: String? = null,
        @SerializedName("is_completed") val isCompleted: Boolean,
        @SerializedName("notes") val notes: String? = null,
    )

    data class DocumentUpdateResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("document_id") val documentId: String,
        @SerializedName("version") val version: Long = 0,
        @SerializedName("error_code") val errorCode: String? = null,
    )

    // --- Boxes ---

    data class BoxAddRequest(
        @SerializedName("barcode") val barcode: String,
        @SerializedName("weight") val weight: Int,
    )

    data class BoxAddResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("box") val box: JsonElement? = null,
        @SerializedName("error") val error: String? = null,
    )

    data class BoxRemoveResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("box_number") val boxNumber: Int,
        @SerializedName("error") val error: String? = null,
    )

    data class BoxConfirmRequest(
        @SerializedName("barcode") val barcode: String,
        @SerializedName("offline_seq") val offlineSeq: Long,
        @SerializedName("client_ts") val clientTs: Long,
    )

    data class BoxConfirmResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("barcode") val barcode: String,
        @SerializedName("was_noop") val wasNoop: Boolean = false,
        @SerializedName("error") val error: String? = null,
    )

    data class BoxLookupResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("box") val box: JsonElement? = null,
        @SerializedName("error") val error: String? = null,
    )

    // --- Lookups / signed URLs ---

    data class ProductLookupResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("product") val product: JsonElement? = null,
        @SerializedName("error") val error: String? = null,
    )

    data class ShipmentLabelResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("document_id") val documentId: String,
        @SerializedName("download_url") val downloadUrl: String? = null,
        @SerializedName("expires_at") val expiresAt: Long = 0,
        @SerializedName("error") val error: String? = null,
    )

    data class ShipmentTrackResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("document_id") val documentId: String,
        @SerializedName("status") val status: String? = null,
        @SerializedName("status_detail") val statusDetail: String? = null,
        @SerializedName("error") val error: String? = null,
    )

    data class LinePhotoUploadUrlResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("document_id") val documentId: String,
        @SerializedName("line_number") val lineNumber: Int,
        @SerializedName("upload_url") val uploadUrl: String? = null,
        @SerializedName("expires_at") val expiresAt: Long = 0,
        @SerializedName("error") val error: String? = null,
    )

    // --- Diagnostics ---

    data class ErrorReportRequest(
        @SerializedName("error_type") val errorType: String,
        @SerializedName("message") val message: String,
        @SerializedName("stack_trace") val stackTrace: String? = null,
        @SerializedName("metadata") val metadata: Map<String, String>? = null,
    )

    data class DebugEventBatchRequest(
        @SerializedName("tenant_id") val tenantId: String,
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("events") val events: List<DebugEvent>,
    )

    data class DebugEvent(
        @SerializedName("id") val id: String,
        @SerializedName("user_id") val userId: String? = null,
        @SerializedName("document_id") val documentId: String? = null,
        @SerializedName("stage") val stage: String? = null,
        @SerializedName("event_type") val eventType: String,
        @SerializedName("severity") val severity: String,
        @SerializedName("message") val message: String,
        @SerializedName("payload_json") val payloadJson: String? = null,
        @SerializedName("created_at") val createdAt: Long,
    )

    data class DebugEventBatchResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("accepted_ids") val acceptedIds: List<String>? = null,
        @SerializedName("error") val error: String? = null,
    )
}
