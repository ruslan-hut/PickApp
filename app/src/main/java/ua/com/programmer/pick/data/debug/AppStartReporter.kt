package ua.com.programmer.pick.data.debug

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
import ua.com.programmer.pick.core.util.CrashReporter
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
                tagCrashReports()
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

    /**
     * Attaches device/tenant identity to Crashlytics reports so a field crash
     * can be matched to the journal export for the same device. Runs even when
     * the journal is disabled — Crashlytics enablement is independent.
     */
    private suspend fun tagCrashReports() {
        if (!CrashReporter.isAvailable) return
        CrashReporter.setUserId(appPreferences.getDeviceIdSync())
        appPreferences.getTenantIdSync()?.let { CrashReporter.setKey("tenant_id", it) }
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
        if (exit != null) {
            payload["last_exit_reason"] = exit.reasonName
            payload["last_exit_at"] = exit.timestamp
            payload["last_exit_description"] = exit.description
            payload["last_exit_importance"] = exit.importance
            exit.signal?.let { payload["last_exit_signal"] = it }
            // System-captured trace exists for ANRs and native crashes —
            // exactly the deaths our own JVM crash handler cannot see.
            exit.trace?.let { payload["last_exit_trace"] = it }

            severity = exit.severity
            message = "app process started; previous exit: ${exit.reasonName}"
        }

        debugJournal.log(
            eventType = DebugEventType.APP_START,
            message = message,
            severity = severity,
            payload = payload
        )
    }

    private suspend fun unreportedLastExit(): ExitInfoReader.Exit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latest = ExitInfoReader.latestExit(context) ?: return null
        if (latest.timestamp <= appPreferences.getLastReportedExitTimestamp()) return null
        appPreferences.setLastReportedExitTimestamp(latest.timestamp)
        return latest
    }
}
