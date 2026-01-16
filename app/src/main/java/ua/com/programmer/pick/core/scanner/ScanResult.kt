package ua.com.programmer.pick.core.scanner

/**
 * Result of a barcode scan operation
 */
sealed class ScanResult {

    /**
     * Successful scan with barcode data
     */
    data class Success(
        val rawValue: String,
        val format: BarcodeFormat,
        val gs1Data: GS1Data? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val source: ScanSource = ScanSource.UNKNOWN
    ) : ScanResult()

    /**
     * Scan failed with error
     */
    data class Error(
        val message: String,
        val exception: Throwable? = null,
        val errorType: ScanErrorType = ScanErrorType.UNKNOWN
    ) : ScanResult()

    /**
     * No barcode found in scan
     */
    data object Empty : ScanResult()
}

/**
 * Source of the scan
 */
enum class ScanSource {
    HARDWARE_SCANNER,
    CAMERA,
    MANUAL_INPUT,
    UNKNOWN
}

/**
 * Types of scan errors
 */
enum class ScanErrorType {
    CAMERA_PERMISSION_DENIED,
    CAMERA_NOT_AVAILABLE,
    SCANNER_NOT_AVAILABLE,
    INVALID_BARCODE,
    PROCESSING_ERROR,
    TIMEOUT,
    UNKNOWN
}

/**
 * Parsed GS1 data from DataMatrix or GS1-128 barcodes
 */
data class GS1Data(
    val gtin: String? = null,           // AI 01 - Global Trade Item Number (14 digits)
    val batchNumber: String? = null,    // AI 10 - Batch/Lot number
    val serialNumber: String? = null,   // AI 21 - Serial number
    val expirationDate: Long? = null,   // AI 17 - Expiration date (YYMMDD)
    val productionDate: Long? = null,   // AI 11 - Production date (YYMMDD)
    val quantity: Int? = null,          // AI 30 - Variable count
    val weight: Double? = null,         // AI 310x - Net weight (kg)
    val rawAIs: Map<String, String> = emptyMap() // All parsed Application Identifiers
) {
    /**
     * Extract the base product code (GTIN-13 or GTIN-14)
     */
    fun getProductBarcode(): String? {
        return gtin?.let {
            when (it.length) {
                14 -> if (it.startsWith("0")) it.substring(1) else it
                13 -> it
                12 -> "0$it" // UPC-A to EAN-13
                8 -> it // EAN-8
                else -> it
            }
        }
    }

    /**
     * Check if this GS1 data has batch tracking info
     */
    fun hasBatchInfo(): Boolean = batchNumber != null || expirationDate != null
}

/**
 * Scanned barcode with additional context
 */
data class ScannedBarcode(
    val rawValue: String,
    val format: BarcodeFormat,
    val gs1Data: GS1Data?,
    val productId: String? = null,
    val productName: String? = null,
    val productCode: String? = null,
    val isKnownProduct: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Get the best available product identifier
     */
    fun getProductIdentifier(): String {
        return productCode ?: gs1Data?.getProductBarcode() ?: rawValue
    }
}
