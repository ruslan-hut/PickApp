package ua.com.programmer.pick.domain.model

// Instance of a box linked to a document during the PACK stage.
// isParcel is denormalized from the master Box at add-time so the UI can
// distinguish delivery places from nested packages without a join.
// Status lifecycle: PACKED -> PICKED_UP -> DELIVERED.
data class DocumentBox(
    val id: String,
    val documentId: String,
    val boxId: String,
    val barcode: String,
    val isParcel: Boolean,
    val weight: Int,
    val status: String,
    val packedBy: String? = null,
    val packedAt: Long? = null,
    val pickedUpBy: String? = null,
    val pickedUpAt: Long? = null,
    val deliveredBy: String? = null,
    val deliveredAt: Long? = null,
    val lastModified: Long,
    val version: Int
)
