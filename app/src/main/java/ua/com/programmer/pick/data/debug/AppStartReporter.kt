package ua.com.programmer.pick.data.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.com.programmer.pick.BuildConfig
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes process deaths visible in the Debug Journal. The journal cannot write
 * anything at the moment the process dies, so crashes/OOM kills leave no trace
 * of their own — field reports of "the app just closed" were undiagnosable
 * from server-side exports alone.
 *
 * Two complementary mechanisms:
 * - On every cold start, an APP_START event carrying the previous process's
 *   exit reason from [ActivityManager.getHistoricalProcessExitReasons]
 *   (API 30+). Already-reported exits are deduped via a persisted timestamp.
 * - A default uncaught-exception handler that writes an APP_CRASH event
 *   synchronously (with the stack trace) before chaining to the platform
 *   handler. Covers JVM crashes on all API levels.
 */
@Singleton
class AppStartReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val debugJournal: DebugJournal,
    private val appPreferences: AppPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "AppStartReporter"
        private const val MAX_TRACE_CHARS = 4_000
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    fun onAppStart() {
        installCrashHandler()
        scope.launch {
            try {
                reportStart()
            } catch (e: Exception) {
                AppLog.w(TAG, "app start report failed: ${e.message}")
            }
        }
    }

    private fun installCrashHandler() {
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                debugJournal.logBlocking(
                    eventType = DebugEventType.APP_CRASH,
                    message = "uncaught exception: ${throwable.javaClass.name}: ${throwable.message}",
                    payload = mapOf(
                        "thread" to thread.name,
                        "stack_trace" to throwable.stackTraceToString().take(MAX_TRACE_CHARS)
                    )
                )
            } catch (_: Throwable) {
                // never mask the original crash
            }
            platformHandler?.uncaughtException(thread, throwable)
        }
    }

    private suspend fun reportStart() {
        // Skipping while disabled also skips the dedupe marker, so an exit
        // that happened just before the journal got enabled is still reported.
        if (!debugJournal.enabled.value) return

        val payload = mutableMapOf<String, Any?>(
            "app_version" to BuildConfig.VERSION_NAME,
            "sdk_int" to Build.VERSION.SDK_INT,
            "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        var severity = DebugJournal.SEVERITY_INFO
        var message = "app process started"

        val exit = unreportedLastExit()
        if (exit != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            payload["last_exit_reason"] = exitReasonName(exit.reason)
            payload["last_exit_at"] = exit.timestamp
            payload["last_exit_description"] = exit.description
            payload["last_exit_importance"] = exit.importance
            if (exit.reason == ApplicationExitInfo.REASON_SIGNALED) {
                payload["last_exit_signal"] = exit.status
            }
            // System-captured trace exists for ANRs and native crashes —
            // exactly the deaths our own JVM crash handler cannot see.
            runCatching {
                exit.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { payload["last_exit_trace"] = it.take(MAX_TRACE_CHARS) }

            severity = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR -> DebugJournal.SEVERITY_ERROR
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> DebugJournal.SEVERITY_WARN
                else -> DebugJournal.SEVERITY_INFO
            }
            message = "app process started; previous exit: ${exitReasonName(exit.reason)}"
        }

        debugJournal.log(
            eventType = DebugEventType.APP_START,
            message = message,
            severity = severity,
            payload = payload
        )
    }

    private suspend fun unreportedLastExit(): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val latest = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        if (latest.timestamp <= appPreferences.getLastReportedExitTimestamp()) return null
        appPreferences.setLastReportedExitTimestamp(latest.timestamp)
        return latest
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
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
