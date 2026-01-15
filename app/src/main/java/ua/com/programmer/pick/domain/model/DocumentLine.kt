package ua.com.programmer.pick.domain.model

data class DocumentLine(
    val id: String,
    val documentId: String,
    val lineNumber: Int,
    val productId: String,
    val productCode: String,
    val productName: String,
    val unit: String,
    val plannedQuantity: Double,
    val actualQuantity: Double,
    val batchNumber: String?,
    val expirationDate: Long?,
    val locationId: String?,
    val locationPath: String?,
    val notes: String?,
    val isCompleted: Boolean = false,
    val isDirty: Boolean = false
)
