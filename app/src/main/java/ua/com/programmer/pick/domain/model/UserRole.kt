package ua.com.programmer.pick.domain.model

enum class UserRole {
    COLLECTOR,
    COURIER,
    ADMINISTRATOR;

    companion object {
        fun fromString(value: String): UserRole = when (value.uppercase()) {
            "COLLECTOR" -> COLLECTOR
            "COURIER" -> COURIER
            "ADMINISTRATOR" -> ADMINISTRATOR
            else -> COLLECTOR
        }
    }
}
