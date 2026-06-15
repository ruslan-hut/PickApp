package ua.com.programmer.pick.data.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.annotation.RequiresApi
import android.os.Build

/**
 * Isolates all [ApplicationExitInfo] access so the class is resolved only on
 * API 30+. Referencing [ApplicationExitInfo] from a method that also runs on
 * older devices makes ART resolve the (absent) class on method entry, before
 * any SDK guard executes — a guaranteed [NoClassDefFoundError] below API 30.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object ExitInfoReader {

    private const val MAX_TRACE_CHARS = 4_000

    /** Flattened, version-safe view of the latest historical process exit. */
    data class Exit(
        val timestamp: Long,
        val reasonName: String,
        val description: String?,
        val importance: Int,
        val signal: Int?,
        val severity: String,
        val trace: String?
    )

    fun latestExit(context: Context): Exit? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null

        val trace = runCatching {
            info.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.take(MAX_TRACE_CHARS)

        return Exit(
            timestamp = info.timestamp,
            reasonName = reasonName(info.reason),
            description = info.description,
            importance = info.importance,
            signal = if (info.reason == ApplicationExitInfo.REASON_SIGNALED) info.status else null,
            severity = severity(info.reason),
            trace = trace
        )
    }

    private fun severity(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR -> DebugJournal.SEVERITY_ERROR
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> DebugJournal.SEVERITY_WARN
        else -> DebugJournal.SEVERITY_INFO
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }
}
