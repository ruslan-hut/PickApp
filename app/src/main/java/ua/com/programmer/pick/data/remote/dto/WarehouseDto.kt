package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO for warehouse received from server
 */
data class WarehouseDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("external_id")
    val externalId: String? = null,

    @SerializedName("code")
    val code: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("is_addressed")
    val isAddressed: Boolean = false,

    @SerializedName("is_active")
    val isActive: Boolean = true,

    @SerializedName("locations")
    val locations: List<WarehouseLocationDto>? = null
)

/**
 * DTO for warehouse location
 */
data class WarehouseLocationDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("row")
    val row: String,

    @SerializedName("shelf")
    val shelf: String,

    @SerializedName("barcode")
    val barcode: String?,

    @SerializedName("is_active")
    val isActive: Boolean = true
)
