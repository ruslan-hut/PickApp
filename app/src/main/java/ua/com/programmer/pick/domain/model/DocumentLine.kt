package ua.com.programmer.pick.domain.model

data class DocumentLine(
    val id: String,
    val documentId: String,
    val lineNumber: Int,
    // The ERP's own stable line id, when supplied. Guided-task line updates
    // address a line by it; classic flows use lineNumber.
    val lineKey: String? = null,
    val productId: String,
    val productCode: String,
    val productName: String,
    val unit: String,
    val volume: Int = 0,
    val volumeUnit: String? = null,
    val plannedQuantity: Double,
    val actualQuantity: Double,
    val batchNumber: String?,
    // Part of actualQuantity collected by batch-label scans; null = no
    // breakdown known on the device (see LineBatchJson).
    val batches: List<LineBatch>? = null,
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
