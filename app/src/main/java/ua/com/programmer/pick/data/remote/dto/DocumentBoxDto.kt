package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

// Matches the server's DocumentBoxSyncDto. Boxes are embedded inside DocumentDto.boxes.
// box_number is the in-document primary key, assigned by the server on BOX_ADD.
// is_parcel is denormalized from the master Box.
data class DocumentBoxDto(
    @SerializedName("box_number")
    val boxNumber: Int,

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
)
