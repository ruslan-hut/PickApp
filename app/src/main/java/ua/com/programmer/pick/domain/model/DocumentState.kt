package ua.com.programmer.pick.domain.model

enum class DocumentState {
    LOADED,
    COLLECTING,
    COLLECTED,
    PACK,
    PACKING,
    PACKED,
    DELIVERY,
    DELIVERING,
    DELIVERED,
    REVIEW,
    SENT,
    ERROR;

    companion object {
        fun fromString(value: String): DocumentState = when (value.uppercase()) {
            "LOADED" -> LOADED
            "COLLECTING" -> COLLECTING
            "COLLECTED" -> COLLECTED
            "PACK" -> PACK
            "PACKING" -> PACKING
            "PACKED" -> PACKED
            "DELIVERY" -> DELIVERY
            "DELIVERING" -> DELIVERING
            "DELIVERED" -> DELIVERED
            "REVIEW" -> REVIEW
            "SENT" -> SENT
            "ERROR" -> ERROR
            else -> LOADED
        }

        /**
         * Returns the stage name for a given state, or null if the state has no
         * stage. REVIEW is a parked, non-stage state — the server never returns
         * a REVIEW document to a worker, so the app only needs to recognize it.
         */
        fun stageOf(state: DocumentState): String? = when (state) {
            LOADED, COLLECTING, COLLECTED -> "collect"
            PACK, PACKING, PACKED -> "pack"
            DELIVERY, DELIVERING, DELIVERED -> "deliver"
            else -> null
        }

        /** Returns true if the state is a stage start state (can be locked). */
        fun isStageStart(state: DocumentState): Boolean = state in listOf(LOADED, PACK, DELIVERY)

        /** Returns true if the state is an in-process state (locked by a worker). */
        fun isInProcess(state: DocumentState): Boolean = state in listOf(COLLECTING, PACKING, DELIVERING)
    }
}
