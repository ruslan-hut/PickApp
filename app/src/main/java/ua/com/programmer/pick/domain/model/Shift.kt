package ua.com.programmer.pick.domain.model

/**
 * Shift marks (server `docs/device-api.md` § "Shift marks"). The worker starts
 * and ends a shift by scanning the rotating QR code on the warehouse TV, or by
 * typing a one-time code an administrator issued. Only the server's time
 * counts, so a mark is online-only: accepted or refused on the spot.
 */
enum class ShiftAction { START, END }

/**
 * What Home shows. [enabled] false — the tenant or the worker's warehouse has
 * no shift marks — means show nothing. [isOpen] false → "shift not started"
 * banner. Every text is server-authored in the tenant's locale.
 */
data class ShiftStatus(
    val enabled: Boolean,
    val isOpen: Boolean = false,
    val startedText: String? = null,
    /** Number of a document the worker is collecting: warn before ending the shift. */
    val collectingDocument: String? = null,
) {
    companion object {
        val DISABLED = ShiftStatus(enabled = false)
    }
}

sealed interface ShiftMarkResult {
    /** [message] — "Зміну розпочато о 09:02", server-authored. */
    data class Success(val message: String?) : ShiftMarkResult

    /** The server's refusal; [message] is the text to show. */
    data class Refused(val code: String, val message: String?) : ShiftMarkResult

    /** No server answer after every retry. */
    data class Unreachable(val message: String?) : ShiftMarkResult
}
