package ua.com.programmer.pick.core.scanner

import ua.com.programmer.pick.core.util.AppLog
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.domain.repository.ProductRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified barcode scanning service.
 * Provides a single API for both hardware and camera scanning,
 * with automatic product lookup.
 */
@Singleton
class BarcodeService @Inject constructor(
    private val hardwareScannerManager: HardwareScannerManager,
    private val cameraScannerManager: CameraScannerManager,
    private val productRepository: ProductRepository,
    private val gs1Parser: GS1Parser,
    val scannerSettings: ScannerSettings,
    val diagnostics: ScannerDiagnostics,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "BarcodeService"
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _scannedBarcodes = MutableSharedFlow<ScannedBarcode>(extraBufferCapacity = 16)
    val scannedBarcodes: SharedFlow<ScannedBarcode> = _scannedBarcodes.asSharedFlow()

    private val _scanErrors = MutableSharedFlow<ScanResult.Error>(extraBufferCapacity = 8)
    val scanErrors: SharedFlow<ScanResult.Error> = _scanErrors.asSharedFlow()

    private val _activeScanner = MutableStateFlow<ScannerType?>(null)
    val activeScanner: StateFlow<ScannerType?> = _activeScanner.asStateFlow()

    private val _isHardwareScannerAvailable = MutableStateFlow(false)
    val isHardwareScannerAvailable: StateFlow<Boolean> = _isHardwareScannerAvailable.asStateFlow()

    private val _isCameraScannerAvailable = MutableStateFlow(false)
    val isCameraScannerAvailable: StateFlow<Boolean> = _isCameraScannerAvailable.asStateFlow()

    private var isInitialized = false

    // Buffer and timing for keyboard-style hardware scanners (key events)
    private val hardwareBarcodeBuffer = StringBuilder()
    private var hardwareLastKeystrokeTime = 0L
    private var barcodeConsumed = false

    // Test mode listener — when set, key events are forwarded here instead of processing
    private var keyEventTestListener: ((KeyEvent) -> Boolean)? = null

    /**
     * Register a listener that intercepts all key events before barcode processing.
     * Used by the scanner test screen for diagnostics.
     */
    fun setKeyEventTestListener(listener: ((KeyEvent) -> Boolean)?) {
        keyEventTestListener = listener
    }

    /**
     * Initialize the barcode service.
     * Call this once during app startup.
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        AppLog.d(TAG, "Initializing BarcodeService")

        // Monitor scanner availability
        hardwareScannerManager.isAvailable
            .onEach { _isHardwareScannerAvailable.value = it }
            .launchIn(scope)

        cameraScannerManager.isAvailable
            .onEach { _isCameraScannerAvailable.value = it }
            .launchIn(scope)

        // Forward hardware scanner results
        hardwareScannerManager.scanResults
            .onEach { handleScanResult(it, ScannerType.HARDWARE) }
            .launchIn(scope)

        // Forward camera scanner results
        cameraScannerManager.scanResults
            .onEach { handleScanResult(it, ScannerType.CAMERA) }
            .launchIn(scope)
    }


    fun onHardwareKeyEvent(event: KeyEvent): Boolean {
        // When a test listener is active, forward all events to it
        keyEventTestListener?.let { listener ->
            if (listener(event)) return true
        }

        diagnostics.recordKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            val isTerminator = scannerSettings.isTerminator(event.keyCode)

            if (isTerminator) {
                if (hardwareBarcodeBuffer.length >= scannerSettings.minBarcodeLength) {
                    val raw = hardwareBarcodeBuffer.toString()
                    val cleaned = scannerSettings.cleanBarcode(raw)
                    AppLog.d(TAG, "barcode: $cleaned")
                    diagnostics.recordDetection(raw, cleaned, success = true)
                    hardwareBarcodeBuffer.clear()
                    barcodeConsumed = true

                    // Process the assembled barcode asynchronously
                    scope.launch {
                        val format = detectBarcodeFormat(cleaned)
                        val gs1Data = if (format.supportsGS1() && gs1Parser.isGS1Barcode(cleaned)) {
                            gs1Parser.parse(cleaned)
                        } else {
                            null
                        }

                        val result = ScanResult.Success(
                            rawValue = cleaned,
                            format = format,
                            gs1Data = gs1Data,
                            source = ScanSource.HARDWARE_SCANNER
                        )

                        handleScanResult(result, ScannerType.HARDWARE)
                    }
                    return true
                }
                // Buffer had some chars but too few — still consume the terminator
                if (hardwareBarcodeBuffer.isNotEmpty()) {
                    AppLog.d(TAG, "barcode too short: $hardwareBarcodeBuffer (${hardwareBarcodeBuffer.length} < ${scannerSettings.minBarcodeLength})")
                    diagnostics.recordDetection(hardwareBarcodeBuffer.toString(), "", success = false)
                    hardwareBarcodeBuffer.clear()
                    barcodeConsumed = false
                    return true
                }
                // Buffer empty — normal keyboard TAB/ENTER, let it through
                diagnostics.recordNote("terminator pass-through (empty buffer)")
                hardwareBarcodeBuffer.clear()
                barcodeConsumed = false
                return false
            }

            val currentTime = System.currentTimeMillis()
            if (hardwareBarcodeBuffer.isNotEmpty() && currentTime - hardwareLastKeystrokeTime > scannerSettings.keystrokeTimeout) {
                diagnostics.recordBuffer("timeout_clear", hardwareBarcodeBuffer.toString(), hardwareBarcodeBuffer.length)
                hardwareBarcodeBuffer.clear()
            }

            val char = event.unicodeChar.toChar()
            if (char.code in 0x20..0x7E) {
                hardwareBarcodeBuffer.append(char)
                hardwareLastKeystrokeTime = currentTime
                diagnostics.recordBuffer("append", hardwareBarcodeBuffer.toString(), hardwareBarcodeBuffer.length)
                return true
            }

            // Non-printable keys — let the system handle them
            return false
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (scannerSettings.isTerminator(event.keyCode)) {
                if (barcodeConsumed) {
                    barcodeConsumed = false
                    return true
                }
            }
        }

        return false
    }

    /**
     * Start hardware scanner.
     * Preferred method on TSD devices.
     */
    fun startHardwareScanner() {
        AppLog.d(TAG, "Starting hardware scanner")
        hardwareScannerManager.startScanning()
        _activeScanner.value = ScannerType.HARDWARE
    }

    /**
     * Stop hardware scanner.
     */
    fun stopHardwareScanner() {
        AppLog.d(TAG, "Stopping hardware scanner")
        hardwareScannerManager.stopScanning()
        if (_activeScanner.value == ScannerType.HARDWARE) {
            _activeScanner.value = null
        }
    }

    /**
     * Get the camera scanner manager for UI integration.
     */
    fun getCameraScannerManager(): CameraScannerManager = cameraScannerManager

    /**
     * Stop camera scanner.
     */
    fun stopCameraScanner() {
        AppLog.d(TAG, "Stopping camera scanner")
        cameraScannerManager.stopScanning()
        if (_activeScanner.value == ScannerType.CAMERA) {
            _activeScanner.value = null
        }
    }

    /**
     * Stop all scanners.
     */
    fun stopAllScanners() {
        stopHardwareScanner()
        stopCameraScanner()
    }

    /**
     * Process a manually entered barcode.
     */
    fun processManualEntry(barcode: String) {
        scope.launch {
            AppLog.d(TAG, "Processing manual entry: $barcode")

            val format = detectBarcodeFormat(barcode)
            val gs1Data = if (format.supportsGS1() && gs1Parser.isGS1Barcode(barcode)) {
                gs1Parser.parse(barcode)
            } else {
                null
            }

            val result = ScanResult.Success(
                rawValue = barcode,
                format = format,
                gs1Data = gs1Data,
                source = ScanSource.MANUAL_INPUT
            )

            handleScanResult(result, null)
        }
    }

    private suspend fun handleScanResult(result: ScanResult, scannerType: ScannerType?) {
        when (result) {
            is ScanResult.Success -> {
                AppLog.d(TAG, "Scan success: ${result.rawValue}")
                val scannedBarcode = enrichWithProductInfo(result)
                _scannedBarcodes.emit(scannedBarcode)
            }
            is ScanResult.Error -> {
                AppLog.e(TAG, "Scan error: ${result.message}")
                _scanErrors.emit(result)
            }
            is ScanResult.Empty -> {
                // No action needed
            }
        }
    }

    private suspend fun enrichWithProductInfo(scanResult: ScanResult.Success): ScannedBarcode {
        // Determine the barcode to search for
        val searchBarcode = scanResult.gs1Data?.getProductBarcode()
            ?: scanResult.rawValue

        // Try to find product by barcode
        val product = try {
            productRepository.getProductByBarcode(searchBarcode)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error looking up product: ${e.message}")
            null
        }

        return if (product != null) {
            ScannedBarcode(
                rawValue = scanResult.rawValue,
                format = scanResult.format,
                gs1Data = scanResult.gs1Data,
                productId = product.id,
                productName = product.name,
                productCode = product.code,
                isKnownProduct = true,
                timestamp = scanResult.timestamp
            )
        } else {
            // Product not found - return barcode without product info
            ScannedBarcode(
                rawValue = scanResult.rawValue,
                format = scanResult.format,
                gs1Data = scanResult.gs1Data,
                productId = null,
                productName = null,
                productCode = null,
                isKnownProduct = false,
                timestamp = scanResult.timestamp
            )
        }
    }

    private fun detectBarcodeFormat(barcode: String): BarcodeFormat {
        val clean = barcode.trim()

        return when {
            // GS1 detection
            clean.startsWith("]d2") || clean.startsWith("]C1") -> BarcodeFormat.DATA_MATRIX
            clean.contains('\u001D') -> BarcodeFormat.DATA_MATRIX

            // Length-based for numeric barcodes
            clean.all { it.isDigit() } -> when (clean.length) {
                8 -> BarcodeFormat.EAN_8
                12 -> BarcodeFormat.UPC_A
                13 -> BarcodeFormat.EAN_13
                14 -> BarcodeFormat.ITF
                else -> BarcodeFormat.CODE_128
            }

            // Alphanumeric
            else -> BarcodeFormat.CODE_128
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        diagnostics.stop()
        diagnostics.clear()
        hardwareScannerManager.release()
        cameraScannerManager.release()
    }
}
