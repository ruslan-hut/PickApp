package ua.com.programmer.pick.domain.model

data class Document(
    val id: String,
    val externalId: String?,
    val type: String,
    val number: String,
    val date: Long,
    val state: DocumentState,
    val clientId: String?,
    val clientName: String?,
    val clientLanguage: String? = null,
    val warehouseId: String?,
    val warehouseName: String?,
    val notes: String?,
    val totalPlanned: Double,
    val totalActual: Double,
    val assignedUserId: String?,
    val assignedWorkerId: String? = null,
    val courierUserId: String? = null,
    val takenAt: Long?,
    val completedAt: Long?,
    val deliveredAt: Long? = null,
    val lastModified: Long,
    val version: Int,
    val isDirty: Boolean = false
)
