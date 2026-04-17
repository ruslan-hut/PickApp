package ua.com.programmer.pick.domain.model

// Instance of a box embedded in a document's Boxes array during the PACK stage.
// boxNumber is the in-document primary key (server-assigned). isParcel is
// denormalized from the master Box at add-time so the UI can distinguish
// delivery places from nested packages without a join.
// Status lifecycle: PACKED -> PICKED_UP -> DELIVERED.
data class DocumentBox(
    val documentId: String,
    val boxNumber: Int,
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
)
