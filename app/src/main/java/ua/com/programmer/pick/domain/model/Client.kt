package ua.com.programmer.pick.domain.model

data class Client(
    val id: String,
    val code: String,
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val isActive: Boolean = true
)
