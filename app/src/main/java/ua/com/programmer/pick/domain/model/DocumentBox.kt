package ua.com.programmer.pick.domain.model

data class DocumentBox(
    val id: String,
    val documentId: String,
    val boxId: String,
    val barcode: String,
    val weight: Int,
    val status: String,
    val collectedBy: String? = null,
    val collectedAt: Long? = null,
    val pickedUpBy: String? = null,
    val pickedUpAt: Long? = null,
    val deliveredBy: String? = null,
    val deliveredAt: Long? = null,
    val lastModified: Long,
    val version: Int
)
