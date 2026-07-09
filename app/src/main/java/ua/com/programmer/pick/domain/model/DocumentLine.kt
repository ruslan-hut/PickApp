package ua.com.programmer.pick.domain.model

data class DocumentLine(
    val id: String,
    val documentId: String,
    val lineNumber: Int,
    val productId: String,
    val productCode: String,
    val productName: String,
    val unit: String,
    val volume: Int = 0,
    val volumeUnit: String? = null,
    val plannedQuantity: Double,
    val actualQuantity: Double,
    val batchNumber: String?,
    val expirationDate: Long?,
    val locationId: String?,
    val locationPath: String?,
    val notes: String?,
    // Unique scan code of this line, when the ERP supplied per-line barcodes.
    // On e-excise documents it is the stamp code and the only thing that
    // distinguishes lines sharing one product name.
    val markCode: String? = null,
    val isCompleted: Boolean = false,
    val isDirty: Boolean = false,
    val hasPhoto: Boolean = false,
    val photoPath: String? = null,
    val photoPending: Boolean = false
)
