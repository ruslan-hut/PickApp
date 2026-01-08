package ua.com.programmer.pick.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class SyncResponseDto(
    @SerializedName("sync_id")
    val syncId: String,
    @SerializedName("entity_type")
    val entityType: String,
    @SerializedName("timestamp")
    val timestamp: Long,
    @SerializedName("is_full_sync")
    val isFullSync: Boolean,
    @SerializedName("data")
    val data: JsonElement,
    @SerializedName("deleted_ids")
    val deletedIds: List<String>? = null
)

data class SyncAckRequest(
    @SerializedName("entity_type")
    val entityType: String,
    @SerializedName("sync_id")
    val syncId: String,
    @SerializedName("timestamp")
    val timestamp: Long
)
