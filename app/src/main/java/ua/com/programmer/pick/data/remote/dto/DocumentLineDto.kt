package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO for document line received from server
 */
data class DocumentLineDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("line_number")
    val lineNumber: Int,

    @SerializedName("product_id")
    val productId: String,

    @SerializedName("product_code")
    val productCode: String? = null,

    @SerializedName("product_name")
    val productName: String? = null,

    @SerializedName("unit")
    val unit: String? = null,

    @SerializedName("planned_quantity")
    val plannedQuantity: Double,

    @SerializedName("actual_quantity")
    val actualQuantity: Double,

    @SerializedName("batch_number")
    val batchNumber: String?,

    @SerializedName("expiration_date")
    val expirationDate: Long?,

    @SerializedName("location_id")
    val locationId: String?,

    @SerializedName("location_path")
    val locationPath: String?,

    @SerializedName("notes")
    val notes: String?,

    @SerializedName("is_completed")
    val isCompleted: Boolean = false
)

/**
 * DTO for document line update request to server
 */
data class DocumentLineUpdateDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("actual_quantity")
    val actualQuantity: Double,

    @SerializedName("batch_number")
    val batchNumber: String?,

    @SerializedName("location_id")
    val locationId: String?,

    @SerializedName("notes")
    val notes: String?,

    @SerializedName("is_completed")
    val isCompleted: Boolean
)

/**
 * DTO for single line update request
 */
data class LineUpdateRequestDto(
    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("line_id")
    val lineId: String,

    @SerializedName("actual_quantity")
    val actualQuantity: Double,

    @SerializedName("batch_number")
    val batchNumber: String?,

    @SerializedName("location_id")
    val locationId: String?,

    @SerializedName("notes")
    val notes: String?,

    @SerializedName("is_completed")
    val isCompleted: Boolean,

    @SerializedName("timestamp")
    val timestamp: Long
)
