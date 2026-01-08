package ua.com.programmer.pick.domain.model

data class Barcode(
    val id: String,
    val productId: String,
    val barcode: String,
    val type: BarcodeType,
    val isPrimary: Boolean = false
)

enum class BarcodeType {
    EAN13,
    EAN8,
    CODE128,
    CODE39,
    QR,
    DATAMATRIX,
    GS1_DATAMATRIX,
    UNKNOWN
}
