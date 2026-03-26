package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

data class BoxDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("external_id")
    val externalId: String? = null,

    @SerializedName("barcode")
    val barcode: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("length")
    val length: Int,

    @SerializedName("width")
    val width: Int,

    @SerializedName("height")
    val height: Int,

    @SerializedName("is_active")
    val isActive: Boolean = true
)
