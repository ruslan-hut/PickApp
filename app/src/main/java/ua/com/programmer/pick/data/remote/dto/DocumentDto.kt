package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO for document header received from server
 */
data class DocumentDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("external_id")
    val externalId: String?,

    @SerializedName("type")
    val type: String,

    @SerializedName("number")
    val number: String,

    @SerializedName("date")
    val date: Long,

    @SerializedName("state")
    val state: String,

    @SerializedName("client_id")
    val clientId: String?,

    @SerializedName("client_name")
    val clientName: String?,

    // Client's preferred communication language, a short ERP-owned label
    // (e.g. "Ukrainian"). Display-only — the worker uses it to pick the right
    // paperwork for the order.
    @SerializedName("client_language")
    val clientLanguage: String? = null,

    @SerializedName("warehouse_id")
    val warehouseId: String?,

    @SerializedName("warehouse_name")
    val warehouseName: String?,

    // "guided" on a Collect-stage document whose warehouse works it as a WMS
    // task; absent = classic screen. Recomputed on every list load, so the
    // tenant's emergency switch flips the screen on the next refresh.
    @SerializedName("collect_mode")
    val collectMode: String? = null,

    @SerializedName("notes")
    val notes: String?,

    @SerializedName("total_planned")
    val totalPlanned: Double,

    @SerializedName("total_actual")
    val totalActual: Double,

    @SerializedName("assigned_user_id")
    val assignedUserId: String?,

    @SerializedName("assigned_worker_id")
    val assignedWorkerId: String? = null,

    @SerializedName("courier_user_id")
    val courierUserId: String? = null,

    @SerializedName("taken_at")
    val takenAt: Long?,

    @SerializedName("completed_at")
    val completedAt: Long?,

    @SerializedName("delivered_at")
    val deliveredAt: Long? = null,

    @SerializedName("last_modified")
    val lastModified: Long,

    @SerializedName("version")
    val version: Int,

    /**
     * True when this LOADED document was returned for partial re-collection
     * after an ERP review. Its per-line is_completed / actual_quantity were
     * pre-seeded by the server and must NOT be zeroed by the LOADED corruption
     * defense in mergeDocumentLines.
     */
    @SerializedName("recollection")
    val recollection: Boolean = false,

    /**
     * True when an admin/tenant user asked the worker holding this document to
     * cooperatively release it. The polling (REST) equivalent of the
     * FORCE_RELEASE_REQUEST push: while this device holds the lock, the
     * orchestrator runs exit-without-saving (drop dirty edits + STAGE_UNLOCK)
     * instead of treating the lock as still ours. Read-only — never sent back.
     */
    @SerializedName("release_requested")
    val releaseRequested: Boolean = false,

    @SerializedName("lines")
    val lines: List<DocumentLineDto>? = null,

    @SerializedName("boxes")
    val boxes: List<DocumentBoxDto>? = null
)

/**
 * DTO for document update request to server
 */
data class DocumentUpdateRequestDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("state")
    val state: String,

    @SerializedName("notes")
    val notes: String?,

    @SerializedName("total_actual")
    val totalActual: Double,

    @SerializedName("version")
    val version: Int,

    @SerializedName("lines")
    val lines: List<DocumentLineUpdateDto>? = null
)

/**
 * DTO for taking document into work
 */
data class TakeDocumentRequestDto(
    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("user_id")
    val userId: String,

    @SerializedName("timestamp")
    val timestamp: Long
)

/**
 * DTO for completing document
 */
data class CompleteDocumentRequestDto(
    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("user_id")
    val userId: String,

    @SerializedName("completed_at")
    val completedAt: Long,

    @SerializedName("version")
    val version: Int,

    @SerializedName("lines")
    val lines: List<DocumentLineUpdateDto>
)

/**
 * Server response for document operations
 */
data class DocumentOperationResponseDto(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("new_version")
    val newVersion: Int?,

    @SerializedName("error")
    val error: String?,

    @SerializedName("error_code")
    val errorCode: String?
)
