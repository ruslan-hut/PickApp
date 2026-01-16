package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO for client received from server
 */
data class ClientDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("code")
    val code: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("address")
    val address: String?,

    @SerializedName("phone")
    val phone: String?,

    @SerializedName("is_active")
    val isActive: Boolean = true
)
