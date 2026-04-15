package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO for product received from server
 */
data class ProductDto(
    @SerializedName("id")
    val id: String,

    // ERP external_id — the canonical identifier used in cross-references
    // (DocumentLine.product_id) on the v2 backend wire format. Optional so the
    // app keeps deserializing payloads from older servers that omit it.
    @SerializedName("external_id")
    val externalId: String? = null,

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
    val barcodes: List<BarcodeDto>? = null,

    @SerializedName("image_url")
    val imageUrl: String? = null
)

/**
 * DTO for product barcode.
 * Server may send barcodes as objects with optional fields.
 */
data class BarcodeDto(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("barcode")
    val barcode: String,

    @SerializedName("type")
    val type: String? = null,

    @SerializedName("is_primary")
    val isPrimary: Boolean = false
)
