package ua.com.programmer.pick.presentation.shift

import ua.com.programmer.pick.domain.model.ShiftAction

data class ShiftMarkUiState(
    val action: ShiftAction = ShiftAction.START,
    val isOnline: Boolean = true,
    val isSubmitting: Boolean = false,
    val adminCode: String = "",
    /** The server's text after an accepted mark; the screen then closes itself. */
    val doneMessage: String? = null,
    val isDone: Boolean = false,
    val error: ShiftMarkError? = null,
)

sealed interface ShiftMarkError {
    /** The server's refusal, shown verbatim (tenant locale). */
    data class Refused(val message: String) : ShiftMarkError
    data object Unreachable : ShiftMarkError
}
