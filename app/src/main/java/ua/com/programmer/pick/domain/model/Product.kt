package ua.com.programmer.pick.domain.model

data class Product(
    val id: String,
    val code: String,
    val name: String,
    val description: String? = null,
    val unit: String,
    val barcodes: List<Barcode> = emptyList(),
    val supportsBatches: Boolean = false,
    val isActive: Boolean = true
)
