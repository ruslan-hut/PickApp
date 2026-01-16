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
            return when (format.uppercase()) {
                "EAN8", "EAN-8", "EAN_8" -> EAN_8
                "EAN13", "EAN-13", "EAN_13" -> EAN_13
                "UPCA", "UPC-A", "UPC_A" -> UPC_A
                "UPCE", "UPC-E", "UPC_E" -> UPC_E
                "CODE39", "CODE-39", "CODE_39" -> CODE_39
                "CODE93", "CODE-93", "CODE_93" -> CODE_93
                "CODE128", "CODE-128", "CODE_128" -> CODE_128
                "ITF", "ITF-14", "INTERLEAVED" -> ITF
                "CODABAR" -> CODABAR
                "QR", "QRCODE", "QR_CODE" -> QR_CODE
                "DATAMATRIX", "DATA_MATRIX", "DATA-MATRIX", "DM" -> DATA_MATRIX
                "PDF417", "PDF_417", "PDF-417" -> PDF_417
                "AZTEC" -> AZTEC
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
