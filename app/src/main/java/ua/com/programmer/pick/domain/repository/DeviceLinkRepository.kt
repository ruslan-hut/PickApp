package ua.com.programmer.pick.domain.repository

import ua.com.programmer.pick.domain.model.DeviceLinkResult
import ua.com.programmer.pick.domain.model.LinkedTenant
import ua.com.programmer.pick.domain.model.PairingState

/** Device-to-tenant linking; both calls work before any worker has signed in. */
interface DeviceLinkRepository {
    /** One pairing poll: registers the device on first contact and keeps its code alive. */
    suspend fun pairing(): DeviceLinkResult<PairingState>

    /** Links the device through a scanned enrollment QR token. */
    suspend fun enroll(token: String): DeviceLinkResult<LinkedTenant>
}
