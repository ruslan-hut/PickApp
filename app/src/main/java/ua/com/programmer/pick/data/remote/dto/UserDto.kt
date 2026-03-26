package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

data class UserDto(
    @SerializedName("id")
    val id: String,
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
