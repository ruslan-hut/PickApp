package ua.com.programmer.pick.data.debug

/**
 * Canonical debug event type codes. Using string constants rather than an enum
 * keeps the on-the-wire contract flexible.
 */
object DebugEventType {
    const val LINE_EDIT = "LINE_EDIT"
    const val LINE_CREATE = "LINE_CREATE"
    const val SYNC_SCHEDULED = "SYNC_SCHEDULED"
    const val SYNC_FIRED = "SYNC_FIRED"
    const val SYNC_FLUSHED = "SYNC_FLUSHED"
    const val DOC_UPDATE_SENT = "DOC_UPDATE_SENT"
    const val DOC_UPDATE_QUEUED = "DOC_UPDATE_QUEUED"
    const val STAGE_LOCK_SENT = "STAGE_LOCK_SENT"
    const val STAGE_LOCK_RESULT = "STAGE_LOCK_RESULT"
    const val STAGE_UNLOCK_SENT = "STAGE_UNLOCK_SENT"
    const val STAGE_COMPLETE_SENT = "STAGE_COMPLETE_SENT"
    const val STAGE_COMPLETE_RESULT = "STAGE_COMPLETE_RESULT"
    const val DOC_DELETED_LOCAL = "DOC_DELETED_LOCAL"
    const val RESYNC_DIRTY = "RESYNC_DIRTY"
    const val WS_SEND_FAIL = "WS_SEND_FAIL"
    const val WS_ACK_TIMEOUT = "WS_ACK_TIMEOUT"
    const val WS_DISCONNECT = "WS_DISCONNECT"
    const val JOURNAL_CONFIG_CHANGED = "JOURNAL_CONFIG_CHANGED"
}
