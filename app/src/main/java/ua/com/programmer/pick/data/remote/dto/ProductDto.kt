package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO for product received from server
 */
data class ProductDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("code")
    val code: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("description")
    val description: String?,

    @SerializedName("unit")
    val unit: String,

    @SerializedName("supports_batches")
    val supportsBatches: Boolean = false,

    @SerializedName("is_active")
    val isActive: Boolean = true,

    @SerializedName("barcodes")
    val barcodes: List<BarcodeDto>? = null
)

/**
 * DTO for product barcode
 */
data class BarcodeDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("barcode")
    val barcode: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("is_primary")
    val isPrimary: Boolean = false
)
