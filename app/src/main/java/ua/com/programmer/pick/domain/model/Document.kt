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
    val isDirty: Boolean = false,
    // "guided" when this Collect-stage document is worked as a WMS task.
    // Server-computed on every list load; absent = classic screen.
    val collectMode: String? = null,
    // Parking: server-decided actions, and while PARKED, why and since when.
    val canPark: Boolean = false,
    val canResume: Boolean = false,
    val parkingReason: String? = null,
    val parkingNote: String? = null,
    val parkedAt: Long? = null,
    // Carrier waybill and the server-decided "print label" action.
    val trackingNumber: String? = null,
    val canPrintLabel: Boolean = false
) {
    val isGuidedCollect: Boolean get() = collectMode == AvailableDocumentType.GUIDED_MODE
}
