package ua.com.programmer.pick.domain.model

// Master-data box from the ERP catalog.
// isParcel is declared by ERP: parcels are delivery "places" (weight required
// when added to a document during PACK); packages nest inside a parcel.
data class Box(
    val id: String,
    val externalId: String?,
    val barcode: String,
    val name: String,
    val length: Int,
    val width: Int,
    val height: Int,
    val isParcel: Boolean = false,
    val isActive: Boolean = true
)
