package ua.com.programmer.pick.domain.model

data class Box(
    val id: String,
    val externalId: String?,
    val barcode: String,
    val name: String,
    val length: Int,
    val width: Int,
    val height: Int,
    val isActive: Boolean = true
)
