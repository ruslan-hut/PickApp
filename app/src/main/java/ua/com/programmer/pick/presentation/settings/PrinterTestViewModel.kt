package ua.com.programmer.pick.presentation.settings

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.printer.LabelSpec
import ua.com.programmer.pick.core.printer.RawPrinterClient
import ua.com.programmer.pick.core.printer.TestLabels
import ua.com.programmer.pick.core.printer.TsplBuilder
import ua.com.programmer.pick.core.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class PrinterTestUiState(
    val host: String = "",
    val port: String = RawPrinterClient.DEFAULT_PORT.toString(),
    val widthMm: String = "85",
    val heightMm: String = "85",
    val gapMm: String = "2",
    val density: String = "8",
    val blackIsZero: Boolean = true,
    val busy: Boolean = false,
    val log: List<String> = emptyList()
)

enum class PrinterTestAction { STATUS, SELF_TEST, TEXT_LABEL, BITMAP_LABEL, GAP_DETECT }

/**
 * Printer diagnostics: talks to a network label printer straight from the
 * terminal, no server involved. The address and label geometry are this
 * screen's own local settings — the real printer registry will come from the
 * server (docs/ttn-printing-plan.md in the backend repo).
 */
@HiltViewModel
class PrinterTestViewModel @Inject constructor(
    @param:ApplicationContext context: Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PrinterTestUiState> = _state.asStateFlow()

    fun onHost(v: String) = _state.update { it.copy(host = v.trim()) }
    fun onPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun onWidth(v: String) = _state.update { it.copy(widthMm = v.filter(Char::isDigit)) }
    fun onHeight(v: String) = _state.update { it.copy(heightMm = v.filter(Char::isDigit)) }
    fun onGap(v: String) = _state.update { it.copy(gapMm = v.filter(Char::isDigit)) }
    fun onDensity(v: String) = _state.update { it.copy(density = v.filter(Char::isDigit)) }
    fun onBlackIsZero(v: Boolean) = _state.update { it.copy(blackIsZero = v) }
    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    fun run(action: PrinterTestAction) {
        val s = _state.value
        if (s.busy) return
        val port = s.port.toIntOrNull()
        val spec = spec(s)
        when {
            s.host.isBlank() -> { log("ERROR: printer address is empty"); return }
            port == null || port !in 1..65535 -> { log("ERROR: bad port"); return }
            spec == null -> { log("ERROR: bad label size (width 20..115, height 10..300 mm)"); return }
        }
        save(s)
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            try {
                when (action) {
                    PrinterTestAction.STATUS -> {
                        log("→ ${s.host}:$port status query")
                        val status = RawPrinterClient.queryStatus(s.host, port!!)
                        log(
                            if (status == null) "✓ connected, no status answer (link OK)"
                            else "✓ connected, status $status"
                        )
                    }
                    PrinterTestAction.SELF_TEST -> send(s.host, port!!, "SELFTEST", TsplBuilder.selfTest())
                    PrinterTestAction.GAP_DETECT -> send(s.host, port!!, "GAPDETECT", TsplBuilder.gapDetect())
                    PrinterTestAction.TEXT_LABEL ->
                        send(s.host, port!!, "text label ${spec!!.widthMm}x${spec.heightMm}", TestLabels.textLabel(spec))
                    PrinterTestAction.BITMAP_LABEL -> {
                        val bytes = withContext(Dispatchers.Default) { TestLabels.bitmapLabel(spec!!, s.blackIsZero) }
                        val polarity = if (s.blackIsZero) "0=black" else "1=black"
                        send(s.host, port!!, "bitmap ${spec!!.widthDots}x${spec.heightDots} dots, $polarity", bytes)
                    }
                }
                log("  ${System.currentTimeMillis() - started} ms")
            } catch (e: Exception) {
                AppLog.w(TAG, "printer test $action failed: ${e.message}")
                log("✗ ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun send(host: String, port: Int, what: String, bytes: ByteArray) {
        log("→ $host:$port $what, ${bytes.size} bytes")
        RawPrinterClient.send(host, port, bytes)
        log("✓ sent")
    }

    private fun spec(s: PrinterTestUiState): LabelSpec? {
        val w = s.widthMm.toIntOrNull() ?: return null
        val h = s.heightMm.toIntOrNull() ?: return null
        if (w !in 20..115 || h !in 10..300) return null
        return LabelSpec(
            widthMm = w,
            heightMm = h,
            gapMm = s.gapMm.toIntOrNull() ?: 2,
            density = s.density.toIntOrNull() ?: 8
        )
    }

    private fun log(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$time $line").takeLast(MAX_LOG)) }
    }

    private fun load() = PrinterTestUiState(
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        port = prefs.getString(KEY_PORT, null) ?: RawPrinterClient.DEFAULT_PORT.toString(),
        widthMm = prefs.getString(KEY_WIDTH, null) ?: "85",
        heightMm = prefs.getString(KEY_HEIGHT, null) ?: "85",
        gapMm = prefs.getString(KEY_GAP, null) ?: "2",
        density = prefs.getString(KEY_DENSITY, null) ?: "8",
        blackIsZero = prefs.getBoolean(KEY_BLACK_IS_ZERO, true),
        log = listOf(TestLabels.device())
    )

    private fun save(s: PrinterTestUiState) = prefs.edit {
        putString(KEY_HOST, s.host)
        putString(KEY_PORT, s.port)
        putString(KEY_WIDTH, s.widthMm)
        putString(KEY_HEIGHT, s.heightMm)
        putString(KEY_GAP, s.gapMm)
        putString(KEY_DENSITY, s.density)
        putBoolean(KEY_BLACK_IS_ZERO, s.blackIsZero)
    }

    private companion object {
        const val TAG = "PrinterTest"
        const val PREFS_NAME = "printer_test"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_WIDTH = "width_mm"
        const val KEY_HEIGHT = "height_mm"
        const val KEY_GAP = "gap_mm"
        const val KEY_DENSITY = "density"
        const val KEY_BLACK_IS_ZERO = "black_is_zero"
        const val MAX_LOG = 100
    }
}
