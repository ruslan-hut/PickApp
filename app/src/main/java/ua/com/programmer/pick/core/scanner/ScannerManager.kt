package ua.com.programmer.pick.core.scanner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Interface for barcode scanner managers.
 * Implemented by both hardware scanner and camera scanner.
 */
interface ScannerManager {

    /**
     * Flow of scan results from this scanner
     */
    val scanResults: SharedFlow<ScanResult>

    /**
     * Whether this scanner is currently available and can be used
     */
    val isAvailable: Flow<Boolean>

    /**
     * Whether scanning is currently active
     */
    val isScanning: Flow<Boolean>

    /**
     * Start listening for scans
     */
    fun startScanning()

    /**
     * Stop listening for scans
     */
    fun stopScanning()

    /**
     * Release resources
     */
    fun release()
}

/**
 * Scanner type identifier
 */
enum class ScannerType {
    HARDWARE,
    CAMERA
}

/**
 * Scanner configuration
 */
data class ScannerConfig(
    val preferredScanner: ScannerType = ScannerType.HARDWARE,
    val enableVibration: Boolean = true,
    val enableSound: Boolean = true,
    val continuousMode: Boolean = false,
    val scanDelay: Long = 500L, // Delay between scans in continuous mode
    val supportedFormats: Set<BarcodeFormat> = BarcodeFormat.entries.toSet()
)
