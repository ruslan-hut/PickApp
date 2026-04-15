package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

data class UserDto(
    @SerializedName("id")
    val id: String,
    // ERP external_id — canonical identifier used in cross-references such as
    // Document.assigned_user_id and DocumentBox.collected_by on the v2 backend
    // wire format. Optional so older payloads still deserialize.
    @SerializedName("external_id")
    val externalId: String? = null,
    @SerializedName("login")
    val login: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("role")
    val role: String,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("warehouse_id")
    val warehouseId: String? = null,
    @SerializedName("last_updated")
    val lastUpdated: Long
)
