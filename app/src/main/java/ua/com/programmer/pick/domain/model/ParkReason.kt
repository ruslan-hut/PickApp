package ua.com.programmer.pick.domain.model

/** One entry of the tenant's park-reason catalog (e.g. "Relabelling"). */
data class ParkReason(
    val id: String,
    val name: String,
)
