package ua.com.programmer.pick.data.debug

/**
 * Canonical debug event type codes. Using string constants rather than an enum
 * keeps the on-the-wire contract flexible.
 */
object DebugEventType {
    const val LINE_EDIT = "LINE_EDIT"
    // Persist step of a line edit failed — the optimistic UI update was reverted
    // and nothing was sent to the server. Captures the case where UI drift (e.g.
    // "Fact: 484" but DB summed to 467) would otherwise go unnoticed.
    const val LINE_EDIT_FAILED = "LINE_EDIT_FAILED"
    const val LINE_CREATE = "LINE_CREATE"
    const val BOX_ADD = "BOX_ADD"
    const val BOX_REMOVE = "BOX_REMOVE"
    const val SYNC_SCHEDULED = "SYNC_SCHEDULED"
    const val SYNC_FIRED = "SYNC_FIRED"
    const val SYNC_FLUSHED = "SYNC_FLUSHED"
    const val DOC_UPDATE_SENT = "DOC_UPDATE_SENT"
    const val DOC_UPDATE_QUEUED = "DOC_UPDATE_QUEUED"
    const val STAGE_LOCK_SENT = "STAGE_LOCK_SENT"
    const val STAGE_LOCK_RESULT = "STAGE_LOCK_RESULT"
    const val STAGE_UNLOCK_SENT = "STAGE_UNLOCK_SENT"
    const val STAGE_PAUSE_SENT = "STAGE_PAUSE_SENT"
    const val STAGE_COMPLETE_SENT = "STAGE_COMPLETE_SENT"
    const val STAGE_COMPLETE_RESULT = "STAGE_COMPLETE_RESULT"
    const val DOC_DELETED_LOCAL = "DOC_DELETED_LOCAL"
    const val RESYNC_DIRTY = "RESYNC_DIRTY"
    // Server-sent document payload was ignored because this device holds the
    // stage lock (document is in an in-process state locally). Symmetric to the
    // backend's `erp_sync_blocked` invariant: while the lock is held, the app
    // owns line actuals / batch / is_completed / boxes, and the server must not
    // overwrite them with a stale echo. See SyncOrchestrator.applyDocumentSync.
    const val DOC_SYNC_SUPPRESSED = "DOC_SYNC_SUPPRESSED"
    // Locally-only line (not in server's line set, not dirty) removed while
    // suppression was active. Catches phantom lines leaked from prior sessions
    // that would otherwise linger until the next unlock-driven resync.
    const val PHANTOM_LINE_PURGED = "PHANTOM_LINE_PURGED"
    const val WS_SEND_FAIL = "WS_SEND_FAIL"
    const val WS_ACK_TIMEOUT = "WS_ACK_TIMEOUT"
    const val WS_DISCONNECT = "WS_DISCONNECT"
    // WebSocket transitioned Disconnected/Reconnecting → Connected. Carries
    // offline_ms, dirty_doc_count, and the list of currently-held stage locks
    // so we can correlate "what changed during the outage" with subsequent
    // SYNC_DATA / DOCUMENT_UPDATE traffic. Without this event the recent
    // offline-edits-lost incident required guessing the reconnect moment.
    const val WS_RECONNECT = "WS_RECONNECT"
    // Counterpart to DOC_SYNC_SUPPRESSED: an inbound server payload was
    // applied to local state. Carries before/after totals + version diff
    // + how many local dirty lines were preserved by mergeDocumentLines.
    // The 05-27.04.26 investigation needed exactly this — to see when
    // and why a local total dropped.
    const val DOC_SYNC_APPLIED = "DOC_SYNC_APPLIED"
    const val JOURNAL_CONFIG_CHANGED = "JOURNAL_CONFIG_CHANGED"
}
