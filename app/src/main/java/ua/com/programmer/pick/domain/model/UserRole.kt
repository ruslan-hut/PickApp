package ua.com.programmer.pick.domain.model

enum class UserRole {
    COLLECTOR,
    COURIER,
    ADMINISTRATOR;

    companion object {
        fun fromString(value: String): UserRole = when (value.uppercase()) {
            "COLLECTOR", "WAREHOUSE_WORKER" -> COLLECTOR
            "COURIER", "PICKER" -> COURIER
            "ADMINISTRATOR" -> ADMINISTRATOR
            else -> COLLECTOR
        }
    }
}
