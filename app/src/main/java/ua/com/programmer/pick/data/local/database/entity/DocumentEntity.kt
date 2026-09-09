package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["type"]),
        Index(value = ["state"]),
        Index(value = ["external_id"]),
        Index(value = ["assigned_user_id"])
    ]
)
data class DocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "external_id")
    val externalId: String?,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "number")
    val number: String,

    @ColumnInfo(name = "date")
    val date: Long,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "client_id")
    val clientId: String?,

    @ColumnInfo(name = "client_name")
    val clientName: String?,

    @ColumnInfo(name = "client_language")
    val clientLanguage: String? = null,

    @ColumnInfo(name = "warehouse_id")
    val warehouseId: String?,

    @ColumnInfo(name = "warehouse_name")
    val warehouseName: String?,

    // "guided" when the warehouse works this Collect-stage document as a WMS
    // task. Server-computed on every list load, so the tenant's emergency
    // switch flips the detail screen on the next refresh.
    @ColumnInfo(name = "collect_mode")
    val collectMode: String? = null,

    @ColumnInfo(name = "notes")
    val notes: String?,

    @ColumnInfo(name = "total_planned")
    val totalPlanned: Double,

    @ColumnInfo(name = "total_actual")
    val totalActual: Double,

    @ColumnInfo(name = "assigned_user_id")
    val assignedUserId: String?,

    @ColumnInfo(name = "assigned_worker_id")
    val assignedWorkerId: String? = null,

    @ColumnInfo(name = "courier_user_id")
    val courierUserId: String? = null,

    @ColumnInfo(name = "taken_at")
    val takenAt: Long?,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    @ColumnInfo(name = "version")
    val version: Int,

    @ColumnInfo(name = "is_dirty")
    val isDirty: Boolean = false
)
