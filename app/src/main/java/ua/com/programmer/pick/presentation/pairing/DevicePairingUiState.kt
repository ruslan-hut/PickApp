package ua.com.programmer.pick.presentation.pairing

import ua.com.programmer.pick.domain.model.DeviceLinkStatus

data class DevicePairingUiState(
    /** Null until the first pairing poll answers. */
    val status: DeviceLinkStatus? = null,
    /** 8 digits to show while PENDING. */
    val code: String? = null,
    val secondsLeft: Int? = null,
    val tenantName: String? = null,
    val deviceName: String? = null,
    val isEnrolling: Boolean = false,
    /** The last poll got no answer; the code on screen may be stale. */
    val isServerUnreachable: Boolean = false,
    val error: PairingError? = null,
    val deviceIdShort: String = "",
)

/** Why the last link attempt did not go through; mapped to text by the screen. */
sealed interface PairingError {
    /** The server refused with an error code (ENROLLMENT_INVALID, DEVICE_LIMIT_REACHED…). */
    data class Refused(val code: String?) : PairingError
    /** The scanned QR code was issued by a different server. */
    data object OtherServer : PairingError
    /** No answer from the server. */
    data object Unreachable : PairingError
}
