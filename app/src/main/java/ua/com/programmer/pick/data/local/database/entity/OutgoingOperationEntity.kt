package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outgoing_operations",
    indices = [
        Index(value = ["status"]),
        Index(value = ["created_at"])
    ]
)
data class OutgoingOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "operation_type")
    val operationType: String,

    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "payload")
    val payload: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "status")
    val status: String
)
