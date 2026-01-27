package ua.com.programmer.pick.data.remote.dto

import com.google.gson.annotations.SerializedName

object AuthDto {

    data class LoginRequest(
        @SerializedName("login")
        val login: String,
        @SerializedName("password")
        val password: String,
        @SerializedName("device_id")
        val deviceId: String? = null
    )

    data class LoginResponse(
        @SerializedName("token")
        val token: String,
        @SerializedName("refresh_token")
        val refreshToken: String,
        @SerializedName("expires_at")
        val expiresAt: Long,
        @SerializedName("user")
        val user: UserDto,
        @SerializedName("offline_hash")
        val offlineHash: String? = null
    )

    data class RefreshRequest(
        @SerializedName("refresh_token")
        val refreshToken: String
    )
}
