package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

data class DocumentBoxDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("box_id")
    val boxId: String,

    @SerializedName("barcode")
    val barcode: String,

    @SerializedName("weight")
    val weight: Int,

    @SerializedName("status")
    val status: String,

    @SerializedName("collected_by")
    val collectedBy: String? = null,

    @SerializedName("collected_at")
    val collectedAt: Long? = null,

    @SerializedName("picked_up_by")
    val pickedUpBy: String? = null,

    @SerializedName("picked_up_at")
    val pickedUpAt: Long? = null,

    @SerializedName("delivered_by")
    val deliveredBy: String? = null,

    @SerializedName("delivered_at")
    val deliveredAt: Long? = null,

    @SerializedName("last_modified")
    val lastModified: Long,

    @SerializedName("version")
    val version: Int
)
