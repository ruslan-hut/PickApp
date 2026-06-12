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
    // Diagnostic trail for the box-scan resolve path during pack:
    // local-cache miss → server fallback (BOX_LOOKUP) → re-query cache.
    // Captures why a "box not found" toast fired even though the box exists
    // on the server (offline WS, server reject, normalization mismatch).
    const val BOX_LOOKUP = "BOX_LOOKUP"
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
    // Transport transitioned Disconnected/Reconnecting → Connected. Carries
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
    // A LOADED document arrived from the server carrying non-zero
    // actual_quantity (or is_completed=true) on one or more lines. Those
    // values were forced to 0/false in mergeDocumentLines before the
    // dirty-preserve step. Catches cross-device propagation (another worker
    // picked the doc and it was unlocked back to LOADED), ERP edits that
    // re-emit stale actuals, server-side mis-mapping, and any other upstream
    // contract violation. Severity ERROR — every occurrence is a sign that
    // either the customer's workflow allows pre-filled actuals (in which
    // case this guard is wrong) or there is real upstream drift to chase.
    const val LOADED_ACTUAL_REJECTED = "LOADED_ACTUAL_REJECTED"
    // M5″ lock-loss recovery succeeded: the server had transiently lost
    // (or admin had soft-released) the worker's lock; a silent STAGE_LOCK
    // re-acquired it within the 3-attempt × 10s budget and any queued or
    // dirty edits were drained on success. Severity INFO — the recovery
    // worked, but the row is the audit trail for "what happened in the
    // gap" so support can correlate with server-side lock churn.
    const val LOCK_LOST_RECOVERED = "LOCK_LOST_RECOVERED"
    // M5″ recovery gave up after 3 failed silent re-lock attempts. The
    // worker's dirty edits for this document were dropped (zeroed),
    // local state was refreshed from the server, and the UI showed a
    // banner. Severity ERROR — this is data loss for that document on
    // this device, intentional but worth flagging. Payload carries the
    // dropped quantities so admins can decide whether to reconcile.
    const val LOCK_LOST_EDIT_DROPPED = "LOCK_LOST_EDIT_DROPPED"
    const val JOURNAL_CONFIG_CHANGED = "JOURNAL_CONFIG_CHANGED"
    // Cold-start marker. The journal writes nothing at the moment a process
    // dies, so without this row a crash/OOM-kill is invisible — the export
    // just shows normal scanning, then normal scanning again. Carries the
    // previous process's exit reason from
    // ActivityManager.getHistoricalProcessExitReasons() (API 30+): severity
    // ERROR for CRASH/CRASH_NATIVE/ANR, WARN for LOW_MEMORY, INFO otherwise.
    const val APP_START = "APP_START"
    // Uncaught JVM exception, written synchronously by the default
    // uncaught-exception handler before the process dies. Complements
    // APP_START: it carries the actual stack trace, and works on devices
    // below API 30 where exit reasons are unavailable.
    const val APP_CRASH = "APP_CRASH"
}
