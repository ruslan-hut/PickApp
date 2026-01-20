package ua.com.programmer.pick.domain.model

data class ProductImage(
    val id: String,
    val productId: String,
    val url: String?,
    val base64: String?
)
