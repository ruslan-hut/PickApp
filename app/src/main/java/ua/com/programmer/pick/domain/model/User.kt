package ua.com.programmer.pick.domain.model

data class User(
    val id: String,
    val login: String,
    val name: String,
    val role: UserRole,
    val isActive: Boolean,
    val lastLoginAt: Long? = null,
    val warehouseId: String? = null
)

/**
 * Login refused for the worker, not the device: [code] is the server's error
 * code (INVALID_CREDENTIALS, USER_INACTIVE…), which the login screen maps to a
 * localized string. The server's own English text is kept as the message, to
 * show when the code is not one the app knows.
 */
class LoginRefusedException(val code: String, message: String) : Exception(message) {
    companion object {
        const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val USER_INACTIVE = "USER_INACTIVE"
    }
}
