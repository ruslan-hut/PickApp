package ua.com.programmer.pick.domain.model

data class Warehouse(
    val id: String,
    val code: String,
    val name: String,
    val isAddressed: Boolean = false,
    val isActive: Boolean = true,
    val locations: List<WarehouseLocation> = emptyList()
)
