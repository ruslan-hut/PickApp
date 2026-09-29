package ua.com.programmer.pick.domain.repository

import ua.com.programmer.pick.domain.model.ParkReason

/**
 * Parking: setting a collected document aside between Collect and Pack
 * (PACK → PARKED) and bringing it back (PARKED → PACK). Online-only — the
 * server decides whether either is allowed; the app offers the actions it is
 * told to (Document.canPark / canResume).
 */
interface ParkingRepository {
    suspend fun reasons(): Result<List<ParkReason>>
    suspend fun park(documentId: String, reasonId: String, note: String?): Result<Unit>
    suspend fun resume(documentId: String): Result<ResumeOutcome>
}

/**
 * The server's answer to a resume: where the document went back to, and
 * whether the worker may take it into work right away (false = they have
 * another document in progress; it reaches them through the queue).
 */
data class ResumeOutcome(val state: String, val version: Int, val readyToWork: Boolean)
