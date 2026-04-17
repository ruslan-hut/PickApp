package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

// Matches the server's DocumentBoxSyncDto. is_parcel is denormalized from the
// master Box. packed_by/at replace the legacy collected_by/at fields since boxing
// now happens during the PACK stage.
data class DocumentBoxDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("box_id")
    val boxId: String,

    @SerializedName("barcode")
    val barcode: String,

    @SerializedName("is_parcel")
    val isParcel: Boolean = false,

    @SerializedName("weight")
    val weight: Int,

    @SerializedName("status")
    val status: String,

    @SerializedName("packed_by")
    val packedBy: String? = null,

    @SerializedName("packed_at")
    val packedAt: Long? = null,

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
