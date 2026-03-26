package ua.com.programmer.pick.domain.model

enum class DocumentState {
    LOADED,
    COLLECTING,
    PACKAGING,
    COLLECTED,
    DELIVERING,
    DELIVERED,
    SENT,
    ERROR;

    companion object {
        fun fromString(value: String): DocumentState = when (value.uppercase()) {
            "LOADED" -> LOADED
            "COLLECTING", "IN_PROGRESS" -> COLLECTING
            "PACKAGING" -> PACKAGING
            "COLLECTED", "COMPLETED" -> COLLECTED
            "DELIVERING" -> DELIVERING
            "DELIVERED" -> DELIVERED
            "SENT" -> SENT
            "ERROR" -> ERROR
            else -> LOADED
        }
    }
}
