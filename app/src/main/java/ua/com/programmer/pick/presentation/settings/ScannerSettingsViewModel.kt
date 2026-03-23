package ua.com.programmer.pick.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.scanner.ScannerDiagnostics
import ua.com.programmer.pick.core.scanner.ScannerSettings
import javax.inject.Inject

data class ScannerSettingsUiState(
    val keystrokeTimeout: String = "",
    val minBarcodeLength: String = "",
    val terminatorKey: String = ScannerSettings.DEFAULT_TERMINATOR,
    val prefixToStrip: String = "",
    val suffixToStrip: String = "",
    val diagEnabled: Boolean = false,
    val diagEventCount: Int = 0,
    val snackbarMessage: String? = null
)

data class ScannerTestUiState(
    val settingsSummary: String = "",
    val lastBarcode: String = "",
    val barcodeInfo: String = "",
    val bufferContent: String = "",
    val bufferInfo: String = "",
    val logLines: String = "",
    val scanCount: Int = 0
)

@HiltViewModel
class ScannerSettingsViewModel @Inject constructor(
    private val barcodeService: BarcodeService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val settings = barcodeService.scannerSettings
    private val diagnostics = barcodeService.diagnostics

    private val _settingsState = MutableStateFlow(ScannerSettingsUiState())
    val settingsState: StateFlow<ScannerSettingsUiState> = _settingsState.asStateFlow()

    private val _testState = MutableStateFlow(ScannerTestUiState())
    val testState: StateFlow<ScannerTestUiState> = _testState.asStateFlow()

    // Test mode internals
    private val testBuffer = StringBuilder()
    private var testLastKeystrokeTime = 0L
    private var testScanCount = 0
    private val testLogLines = StringBuilder()
    private var testLogLineCount = 0

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _settingsState.update {
            it.copy(
                keystrokeTimeout = settings.keystrokeTimeout.toString(),
                minBarcodeLength = settings.minBarcodeLength.toString(),
                terminatorKey = settings.terminatorKey,
                prefixToStrip = settings.prefixToStrip,
                suffixToStrip = settings.suffixToStrip,
                diagEnabled = diagnostics.enabled,
                diagEventCount = diagnostics.eventCount
            )
        }
    }

    fun updateKeystrokeTimeout(value: String) {
        _settingsState.update { it.copy(keystrokeTimeout = value) }
    }

    fun updateMinBarcodeLength(value: String) {
        _settingsState.update { it.copy(minBarcodeLength = value) }
    }

    fun updateTerminatorKey(value: String) {
        _settingsState.update { it.copy(terminatorKey = value) }
    }

    fun updatePrefixToStrip(value: String) {
        _settingsState.update { it.copy(prefixToStrip = value) }
    }

    fun updateSuffixToStrip(value: String) {
        _settingsState.update { it.copy(suffixToStrip = value) }
    }

    fun saveSettings() {
        val state = _settingsState.value
        settings.keystrokeTimeout = state.keystrokeTimeout.toLongOrNull() ?: ScannerSettings.DEFAULT_TIMEOUT
        settings.minBarcodeLength = state.minBarcodeLength.toIntOrNull() ?: ScannerSettings.DEFAULT_MIN_LENGTH
        settings.terminatorKey = state.terminatorKey
        settings.prefixToStrip = state.prefixToStrip
        settings.suffixToStrip = state.suffixToStrip

        // Reload to reflect coerced values
        loadSettings()
        _settingsState.update { it.copy(snackbarMessage = SAVED) }
    }

    fun toggleDiagnostics(enabled: Boolean) {
        if (enabled) {
            diagnostics.start()
        } else {
            diagnostics.stop()
        }
        _settingsState.update {
            it.copy(
                diagEnabled = diagnostics.enabled,
                diagEventCount = diagnostics.eventCount
            )
        }
    }

    fun copyDiagnosticsToClipboard() {
        if (diagnostics.eventCount == 0) {
            _settingsState.update { it.copy(snackbarMessage = NO_DATA) }
            return
        }
        val text = diagnostics.exportAsText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Scanner Diagnostics", text))
        _settingsState.update { it.copy(snackbarMessage = COPIED) }
    }

    fun clearSnackbar() {
        _settingsState.update { it.copy(snackbarMessage = null) }
    }

    // --- Scanner Test Mode ---

    fun startTestMode() {
        testBuffer.clear()
        testLastKeystrokeTime = 0L
        testScanCount = 0
        testLogLines.clear()
        testLogLineCount = 0

        updateTestSettingsSummary()

        barcodeService.setKeyEventTestListener { event ->
            onTestKeyEvent(event)
        }
    }

    fun stopTestMode() {
        barcodeService.setKeyEventTestListener(null)
    }

    fun clearTestLog() {
        testLogLines.clear()
        testLogLineCount = 0
        testScanCount = 0
        testBuffer.clear()
        _testState.update {
            ScannerTestUiState(settingsSummary = it.settingsSummary)
        }
    }

    private fun updateTestSettingsSummary() {
        val terminator = when (settings.terminatorKey) {
            ScannerSettings.TERMINATOR_ENTER -> "Enter"
            ScannerSettings.TERMINATOR_TAB -> "Tab"
            else -> "Enter+Tab"
        }
        val prefix = settings.prefixToStrip.ifEmpty { "-" }
        val suffix = settings.suffixToStrip.ifEmpty { "-" }
        val summary = "Timeout: ${settings.keystrokeTimeout}ms | Min: ${settings.minBarcodeLength} | Term: $terminator | Pfx: $prefix | Sfx: $suffix"
        _testState.update { it.copy(settingsSummary = summary) }
    }

    private fun onTestKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isTerminator = settings.isTerminator(event.keyCode)
            val keyName = KeyEvent.keyCodeToString(event.keyCode)
            val char = event.unicodeChar.toChar()
            val currentTime = System.currentTimeMillis()

            if (isTerminator) {
                val gap = if (testLastKeystrokeTime > 0) currentTime - testLastKeystrokeTime else 0
                appendTestLog("$keyName [TERMINATOR] gap=${gap}ms buf=\"$testBuffer\" len=${testBuffer.length}")

                if (testBuffer.length >= settings.minBarcodeLength) {
                    val raw = testBuffer.toString()
                    val cleaned = settings.cleanBarcode(raw)
                    testScanCount++

                    val info = buildString {
                        append("Scan #$testScanCount")
                        append(" | Length: ${cleaned.length}")
                        if (raw != cleaned) append(" | Raw: $raw")
                    }

                    appendTestLog(">>> BARCODE DETECTED: \"$cleaned\"")

                    _testState.update {
                        it.copy(
                            lastBarcode = cleaned,
                            barcodeInfo = info,
                            scanCount = testScanCount
                        )
                    }
                } else {
                    appendTestLog("--- buffer too short (${testBuffer.length} < ${settings.minBarcodeLength}), ignored")
                }

                testBuffer.clear()
                testLastKeystrokeTime = 0L
                updateTestBufferDisplay()
                return true
            }

            // Timeout check
            if (testBuffer.isNotEmpty() && testLastKeystrokeTime > 0 && currentTime - testLastKeystrokeTime > settings.keystrokeTimeout) {
                appendTestLog("--- timeout (${currentTime - testLastKeystrokeTime}ms > ${settings.keystrokeTimeout}ms), buffer cleared")
                testBuffer.clear()
            }

            if (char.code in 0x20..0x7E) {
                val gap = if (testLastKeystrokeTime > 0) currentTime - testLastKeystrokeTime else 0
                testBuffer.append(char)
                testLastKeystrokeTime = currentTime
                appendTestLog("'$char' ($keyName) gap=${gap}ms")
                updateTestBufferDisplay()
            } else {
                appendTestLog("$keyName (non-printable, skipped)")
            }

            return true
        }

        // Consume ACTION_UP silently
        if (event.action == KeyEvent.ACTION_UP) {
            return true
        }

        return false
    }

    private fun updateTestBufferDisplay() {
        _testState.update {
            it.copy(
                bufferContent = testBuffer.toString(),
                bufferInfo = "${testBuffer.length} chars (min: ${settings.minBarcodeLength})"
            )
        }
    }

    private fun appendTestLog(line: String) {
        if (testLogLineCount >= MAX_LOG_LINES) {
            val firstNewline = testLogLines.indexOf('\n')
            if (firstNewline >= 0) {
                testLogLines.delete(0, firstNewline + 1)
            }
            testLogLineCount--
        }
        if (testLogLines.isNotEmpty()) testLogLines.append('\n')
        testLogLines.append(line)
        testLogLineCount++

        _testState.update { it.copy(logLines = testLogLines.toString()) }
    }

    companion object {
        private const val MAX_LOG_LINES = 100
        const val SAVED = "SAVED"
        const val COPIED = "COPIED"
        const val NO_DATA = "NO_DATA"
    }
}
