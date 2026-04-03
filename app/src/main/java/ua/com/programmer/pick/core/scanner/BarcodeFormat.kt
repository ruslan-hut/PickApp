package ua.com.programmer.pick.core.scanner

/**
 * Supported barcode formats for scanning
 */
enum class BarcodeFormat {
    // 1D Barcodes
    EAN_8,
    EAN_13,
    UPC_A,
    UPC_E,
    CODE_39,
    CODE_93,
    CODE_128,
    ITF,
    CODABAR,

    // 2D Barcodes
    QR_CODE,
    DATA_MATRIX,
    PDF_417,
    AZTEC,

    // Unknown/unsupported
    UNKNOWN;

    companion object {
        /**
         * Convert ML Kit barcode format to our format
         */
        fun fromMlKitFormat(mlKitFormat: Int): BarcodeFormat {
            return when (mlKitFormat) {
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8 -> EAN_8
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13 -> EAN_13
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A -> UPC_A
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E -> UPC_E
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_39 -> CODE_39
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_93 -> CODE_93
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128 -> CODE_128
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ITF -> ITF
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODABAR -> CODABAR
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE -> QR_CODE
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX -> DATA_MATRIX
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417 -> PDF_417
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_AZTEC -> AZTEC
                else -> UNKNOWN
            }
        }

        /**
         * Parse format from string (for hardware scanner type hints)
         */
        fun fromString(format: String): BarcodeFormat {
            val upper = format.uppercase()
            return when {
                upper.contains("EAN8") || upper.contains("EAN-8") || upper.contains("EAN_8") -> EAN_8
                upper.contains("EAN13") || upper.contains("EAN-13") || upper.contains("EAN_13") -> EAN_13
                upper.contains("UPCA") || upper.contains("UPC-A") || upper.contains("UPC_A") -> UPC_A
                upper.contains("UPCE") || upper.contains("UPC-E") || upper.contains("UPC_E") -> UPC_E
                upper.contains("CODE39") || upper.contains("CODE-39") || upper.contains("CODE_39") -> CODE_39
                upper.contains("CODE93") || upper.contains("CODE-93") || upper.contains("CODE_93") -> CODE_93
                upper.contains("CODE128") || upper.contains("CODE-128") || upper.contains("CODE_128") -> CODE_128
                upper.contains("ITF") || upper.contains("INTERLEAVED") || upper.contains("I2OF5") -> ITF
                upper.contains("CODABAR") -> CODABAR
                upper.contains("QR") -> QR_CODE
                upper.contains("DATAMATRIX") || upper.contains("DATA_MATRIX") || upper.contains("DATA-MATRIX") -> DATA_MATRIX
                upper.contains("PDF417") || upper.contains("PDF_417") -> PDF_417
                upper.contains("AZTEC") -> AZTEC
                else -> UNKNOWN
            }
        }
    }

    /**
     * Check if this is a 2D barcode format
     */
    fun is2D(): Boolean = this in listOf(QR_CODE, DATA_MATRIX, PDF_417, AZTEC)

    /**
     * Check if this format can contain GS1 data
     */
    fun supportsGS1(): Boolean = this in listOf(DATA_MATRIX, QR_CODE, EAN_13, EAN_8, CODE_128)
}
