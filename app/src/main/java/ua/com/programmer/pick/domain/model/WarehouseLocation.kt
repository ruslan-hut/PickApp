package ua.com.programmer.pick.domain.model

data class WarehouseLocation(
    val id: String,
    val warehouseId: String,
    val row: String,
    val shelf: String,
    val barcode: String? = null,
    val isActive: Boolean = true
)
