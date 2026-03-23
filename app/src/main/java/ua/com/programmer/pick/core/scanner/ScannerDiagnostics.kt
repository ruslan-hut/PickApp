package ua.com.programmer.pick.core.scanner

import android.os.Build
import android.view.KeyEvent
import ua.com.programmer.pick.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records detailed scanner key event diagnostics when enabled.
 * Data can be exported to clipboard as a structured text report.
 *
 * Automatically disables after [AUTO_DISABLE_MINUTES] minutes or
 * [MAX_EVENTS] recorded events to prevent excessive data accumulation.
 */
@Singleton
class ScannerDiagnostics @Inject constructor(
    private val settings: ScannerSettings
) {
    private val events = mutableListOf<DiagnosticEvent>()
    private var sessionStart: Long = 0
    private var _enabled = false

    val enabled: Boolean get() = _enabled
    val eventCount: Int get() = events.size

    fun start() {
        events.clear()
        sessionStart = System.currentTimeMillis()
        _enabled = true
        recordMeta()
    }

    fun stop() {
        _enabled = false
    }

    /** Record a raw key event from dispatchKeyEvent. */
    fun recordKeyEvent(event: KeyEvent) {
        if (!_enabled) return
        checkAutoDisable()

        events.add(
            DiagnosticEvent(
                timestamp = System.currentTimeMillis(),
                type = "key",
                data = buildString {
                    append(if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP")
                    append(" keyCode=${event.keyCode}")
                    append(" (${KeyEvent.keyCodeToString(event.keyCode)})")
                    val char = event.unicodeChar.toChar()
                    if (char.code in 0x20..0x7E) append(" char='$char'")
                    append(" scanCode=${event.scanCode}")
                    append(" device=${event.device?.name ?: "null"}")
                    append(" source=0x${Integer.toHexString(event.source)}")
                }
            )
        )
    }

    /** Record buffer state change. */
    fun recordBuffer(action: String, bufferContent: String, bufferLength: Int) {
        if (!_enabled) return

        events.add(
            DiagnosticEvent(
                timestamp = System.currentTimeMillis(),
                type = "buffer",
                data = "$action content=\"$bufferContent\" length=$bufferLength"
            )
        )
    }

    /** Record barcode detection result. */
    fun recordDetection(raw: String, cleaned: String, success: Boolean) {
        if (!_enabled) return

        events.add(
            DiagnosticEvent(
                timestamp = System.currentTimeMillis(),
                type = "detect",
                data = buildString {
                    append(if (success) "OK" else "FAIL")
                    append(" raw=\"$raw\" cleaned=\"$cleaned\" length=${cleaned.length}")
                }
            )
        )
    }

    /** Record a custom note (e.g., timeout fired, terminator consumed). */
    fun recordNote(note: String) {
        if (!_enabled) return

        events.add(
            DiagnosticEvent(
                timestamp = System.currentTimeMillis(),
                type = "note",
                data = note
            )
        )
    }

    /** Export all recorded data as a structured text report. */
    fun exportAsText(): String = buildString {
        appendLine("=== Scanner Diagnostics Report ===")
        appendLine()

        appendLine("[Device]")
        appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        appendLine()

        appendLine("[Scanner Settings]")
        appendLine("Timeout: ${settings.keystrokeTimeout} ms")
        appendLine("Min length: ${settings.minBarcodeLength}")
        appendLine("Terminator: ${settings.terminatorKey}")
        appendLine("Prefix: \"${settings.prefixToStrip}\"")
        appendLine("Suffix: \"${settings.suffixToStrip}\"")
        appendLine()

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        appendLine("[Session]")
        appendLine("Started: ${formatter.format(Date(sessionStart))}")
        appendLine("Events: ${events.size}")
        appendLine("Duration: ${(System.currentTimeMillis() - sessionStart) / 1000}s")
        appendLine()

        appendLine("[Events]")
        var prevTime = sessionStart
        for (event in events) {
            val gap = event.timestamp - prevTime
            appendLine("+${gap}ms [${event.type}] ${event.data}")
            prevTime = event.timestamp
        }
    }

    fun clear() {
        events.clear()
    }

    private fun recordMeta() {
        events.add(
            DiagnosticEvent(
                timestamp = System.currentTimeMillis(),
                type = "meta",
                data = "session_start device=${Build.MANUFACTURER}/${Build.MODEL} " +
                        "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                        "app=${BuildConfig.VERSION_NAME}"
            )
        )
    }

    private fun checkAutoDisable() {
        if (events.size >= MAX_EVENTS) {
            _enabled = false
            return
        }
        if (System.currentTimeMillis() - sessionStart > AUTO_DISABLE_MINUTES * 60_000) {
            _enabled = false
        }
    }

    private data class DiagnosticEvent(
        val timestamp: Long,
        val type: String,
        val data: String
    )

    companion object {
        private const val MAX_EVENTS = 2000
        private const val AUTO_DISABLE_MINUTES = 10
    }
}
