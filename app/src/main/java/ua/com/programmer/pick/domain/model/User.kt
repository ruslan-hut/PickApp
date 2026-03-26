package ua.com.programmer.pick.domain.model

data class User(
    val id: String,
    val login: String,
    val name: String,
    val role: UserRole,
    val isActive: Boolean,
    val lastLoginAt: Long? = null,
    val operatingMode: OperatingMode = OperatingMode.RECEIPT,
    val warehouseId: String? = null
)
