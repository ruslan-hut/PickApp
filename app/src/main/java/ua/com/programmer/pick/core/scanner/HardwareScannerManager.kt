package ua.com.programmer.pick.core.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import ua.com.programmer.pick.core.util.AppLog
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware scanner manager for TSD devices.
 * Supports Honeywell, Zebra/Symbol, and generic hardware scanners.
 */
@Singleton
class HardwareScannerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gs1Parser: GS1Parser,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ScannerManager {

    companion object {
        private const val TAG = "HardwareScannerManager"

        // Honeywell scanner intents
        private const val HONEYWELL_ACTION = "com.honeywell.aidc.action.BARCODE_DECODING"
        private const val HONEYWELL_DATA = "com.honeywell.aidc.extra.BARCODE_DATA"
        private const val HONEYWELL_TYPE = "com.honeywell.aidc.extra.BARCODE_TYPE"

        // Zebra/Symbol DataWedge intents
        // Configure DataWedge Intent Output action to match ZEBRA_INTENT_ACTION
        private const val ZEBRA_INTENT_ACTION = "ua.com.programmer.pick.SCAN"
        private const val ZEBRA_DATA = "com.symbol.datawedge.data_string"
        private const val ZEBRA_TYPE = "com.symbol.datawedge.label_type"
        private const val ZEBRA_PACKAGE = "com.symbol.datawedge"

        // Generic scanner intent (used by many Chinese TSD devices)
        private const val GENERIC_ACTION = "device.scanner.BARCODE_READ"
        private const val GENERIC_ACTION_2 = "android.intent.ACTION_DECODE_DATA"
        private const val GENERIC_DATA = "barcode_string"
        private const val GENERIC_DATA_2 = "barcode"
        private const val GENERIC_TYPE = "barcode_type"

        // UROVO scanner intents
        private const val UROVO_ACTION = "android.intent.ACTION_DECODE_DATA"
        private const val UROVO_DATA = "barcode_string"
        private const val UROVO_TYPE = "barcode_type"

        // iData scanner intents
        private const val IDATA_ACTION = "android.intent.action.SCANRESULT"
        private const val IDATA_DATA = "value"
        private const val IDATA_TYPE = "type"
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _scanResults = MutableSharedFlow<ScanResult>(extraBufferCapacity = 16)
    override val scanResults: SharedFlow<ScanResult> = _scanResults.asSharedFlow()

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _isAvailable.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: Flow<Boolean> = _isScanning.asStateFlow()

    private var isReceiverRegistered = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            AppLog.d(TAG, "Received intent: ${intent.action}")
            logIntentExtras(intent)

            val (barcode, formatHint) = extractBarcodeData(intent)
            if (barcode != null) {
                processScan(barcode, formatHint)
            } else {
                AppLog.w(TAG, "No barcode data extracted from intent: ${intent.action}")
            }
        }
    }

    init {
        // Check if hardware scanner is available
        checkScannerAvailability()
    }

    private fun checkScannerAvailability() {
        // Hardware scanner is assumed available on TSD devices
        // In production, you could check for specific scanner packages
        val isTsdDevice = Build.MANUFACTURER.lowercase().let {
            it.contains("honeywell") ||
            it.contains("zebra") ||
            it.contains("symbol") ||
            it.contains("urovo") ||
            it.contains("idata") ||
            it.contains("chainway") ||
            it.contains("newland")
        }

        _isAvailable.value = isTsdDevice || hasHardwareScannerApp()
        AppLog.d(TAG, "Hardware scanner available: ${_isAvailable.value}")
    }

    private fun hasHardwareScannerApp(): Boolean {
        val packageManager = context.packageManager
        val scannerPackages = listOf(
            "com.honeywell.aidc",
            "com.symbol.datawedge",
            "com.zebra.datawedge",
            "com.seuic.scanner",
            "com.android.scanner"
        )

        return scannerPackages.any { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun startScanning() {
        if (isReceiverRegistered) {
            AppLog.d(TAG, "Scanner already started")
            return
        }

        AppLog.d(TAG, "Starting hardware scanner")

        val filter = IntentFilter().apply {
            // Honeywell
            addAction(HONEYWELL_ACTION)
            // Zebra/Symbol DataWedge
            addAction(ZEBRA_INTENT_ACTION)
            // Generic
            addAction(GENERIC_ACTION)
            addAction(GENERIC_ACTION_2)
            // UROVO
            addAction(UROVO_ACTION)
            // iData
            addAction(IDATA_ACTION)

            addCategory(Intent.CATEGORY_DEFAULT)
        }

        ContextCompat.registerReceiver(
            context,
            scanReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        isReceiverRegistered = true
        _isScanning.value = true

        // Enable scanner on some devices
        enableHardwareScanner()
    }

    override fun stopScanning() {
        if (!isReceiverRegistered) {
            AppLog.d(TAG, "Scanner not running")
            return
        }

        AppLog.d(TAG, "Stopping hardware scanner")

        try {
            context.unregisterReceiver(scanReceiver)
        } catch (e: Exception) {
            AppLog.w(TAG, "Error unregistering receiver: ${e.message}")
        }

        isReceiverRegistered = false
        _isScanning.value = false

        // Disable scanner on some devices
        disableHardwareScanner()
    }

    override fun release() {
        stopScanning()
    }

    private fun logIntentExtras(intent: Intent) {
        val extras = intent.extras ?: return
        val sb = StringBuilder("Intent extras for ${intent.action}: ")
        for (key in extras.keySet()) {
            val value = extras.get(key)
            val display = when (value) {
                is ByteArray -> "ByteArray(${value.size})"
                is String -> "\"${value.take(100)}\""
                else -> value?.toString()?.take(100)
            }
            sb.append("$key=$display, ")
        }
        AppLog.d(TAG, sb.toString())
    }

    private fun extractBarcodeData(intent: Intent): Pair<String?, String?> {
        val action = intent.action ?: return Pair(null, null)

        return when {
            action.contains("honeywell", ignoreCase = true) -> {
                Pair(
                    intent.getStringExtra(HONEYWELL_DATA),
                    intent.getStringExtra(HONEYWELL_TYPE)
                )
            }
            action == ZEBRA_INTENT_ACTION -> {
                // Try standard DataWedge extra, then fallback alternatives
                val barcode = intent.getStringExtra(ZEBRA_DATA)
                    ?: intent.getStringExtra("com.symbol.datawedge.decode_data")
                    ?: intent.getStringExtra("com.motorolasolutions.emdk.datawedge.data_string")
                    ?: intent.getStringExtra("com.motorolasolutions.emdk.datawedge.decode_data")
                Pair(
                    barcode,
                    intent.getStringExtra(ZEBRA_TYPE)
                        ?: intent.getStringExtra("com.symbol.datawedge.decode_mode")
                )
            }
            action == UROVO_ACTION -> {
                Pair(
                    intent.getStringExtra(UROVO_DATA),
                    intent.getStringExtra(UROVO_TYPE)
                )
            }
            action == IDATA_ACTION -> {
                Pair(
                    intent.getStringExtra(IDATA_DATA),
                    intent.getStringExtra(IDATA_TYPE)
                )
            }
            else -> {
                // Generic extraction - try common extra names
                val barcode = intent.getStringExtra(GENERIC_DATA)
                    ?: intent.getStringExtra(GENERIC_DATA_2)
                    ?: intent.getStringExtra("data")
                    ?: intent.getStringExtra("scan_result")

                val type = intent.getStringExtra(GENERIC_TYPE)
                    ?: intent.getStringExtra("type")

                Pair(barcode, type)
            }
        }
    }

    private fun processScan(barcode: String, formatHint: String?) {
        AppLog.d(TAG, "Processing scan: $barcode (format hint: $formatHint)")

        scope.launch {
            try {
                val format = formatHint?.let { BarcodeFormat.fromString(it) }
                    ?: detectFormat(barcode)

                val gs1Data = if (format.supportsGS1() && gs1Parser.isGS1Barcode(barcode)) {
                    gs1Parser.parse(barcode)
                } else {
                    null
                }

                val result = ScanResult.Success(
                    rawValue = barcode,
                    format = format,
                    gs1Data = gs1Data,
                    source = ScanSource.HARDWARE_SCANNER
                )

                _scanResults.emit(result)
            } catch (e: Exception) {
                AppLog.e(TAG, "Error processing scan: ${e.message}", e)
                _scanResults.emit(
                    ScanResult.Error(
                        message = "Failed to process barcode: ${e.message}",
                        exception = e,
                        errorType = ScanErrorType.PROCESSING_ERROR
                    )
                )
            }
        }
    }

    private fun detectFormat(barcode: String): BarcodeFormat {
        val clean = barcode.trim()

        return when {
            // GS1 DataMatrix detection
            clean.startsWith("]d2") || clean.startsWith("]C1") -> BarcodeFormat.DATA_MATRIX
            clean.contains('\u001D') -> BarcodeFormat.DATA_MATRIX // Contains GS character

            // Length-based detection for common formats
            clean.length == 8 && clean.all { it.isDigit() } -> BarcodeFormat.EAN_8
            clean.length == 12 && clean.all { it.isDigit() } -> BarcodeFormat.UPC_A
            clean.length == 13 && clean.all { it.isDigit() } -> BarcodeFormat.EAN_13
            clean.length == 14 && clean.all { it.isDigit() } -> BarcodeFormat.ITF

            // QR codes often contain URLs or longer text
            clean.startsWith("http") || clean.length > 50 -> BarcodeFormat.QR_CODE

            // Default to Code 128 for alphanumeric
            clean.any { it.isLetter() } -> BarcodeFormat.CODE_128

            else -> BarcodeFormat.UNKNOWN
        }
    }

    private fun enableHardwareScanner() {
        // Send intents to enable hardware scanner on some devices
        try {
            // Zebra DataWedge enable
            val zebraIntent = Intent("com.symbol.datawedge.api.ACTION")
            zebraIntent.setPackage(ZEBRA_PACKAGE)
            zebraIntent.putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "ENABLE_PLUGIN")
            context.sendBroadcast(zebraIntent)

            // Honeywell enable
            val honeywellIntent = Intent("com.honeywell.aidc.action.ACTION_CONTROL_SCANNER")
            honeywellIntent.putExtra("com.honeywell.aidc.extra.EXTRA_SCAN", true)
            context.sendBroadcast(honeywellIntent)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to enable hardware scanner: ${e.message}")
        }
    }

    private fun disableHardwareScanner() {
        try {
            // Zebra DataWedge disable
            val zebraIntent = Intent("com.symbol.datawedge.api.ACTION")
            zebraIntent.setPackage(ZEBRA_PACKAGE)
            zebraIntent.putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "DISABLE_PLUGIN")
            context.sendBroadcast(zebraIntent)

            // Honeywell disable
            val honeywellIntent = Intent("com.honeywell.aidc.action.ACTION_CONTROL_SCANNER")
            honeywellIntent.putExtra("com.honeywell.aidc.extra.EXTRA_SCAN", false)
            context.sendBroadcast(honeywellIntent)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to disable hardware scanner: ${e.message}")
        }
    }
}
