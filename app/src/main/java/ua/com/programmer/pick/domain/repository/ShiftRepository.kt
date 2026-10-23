package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ua.com.programmer.pick.domain.model.ShiftAction
import ua.com.programmer.pick.domain.model.ShiftMarkResult
import ua.com.programmer.pick.domain.model.ShiftStatus

/** The signed-in worker's shift. Nothing is persisted: the server is asked every time. */
interface ShiftRepository {
    /** Last known status; [ShiftStatus.DISABLED] until the first answer and after logout. */
    val status: StateFlow<ShiftStatus>

    /** Re-reads the status; keeps the last one when the server cannot be reached. */
    suspend fun refresh()

    /** Starts or ends the shift with exactly one of [qr] / [adminCode]. */
    suspend fun mark(action: ShiftAction, qr: String? = null, adminCode: String? = null): ShiftMarkResult
}
