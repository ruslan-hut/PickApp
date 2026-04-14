package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debug_journal_events",
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["created_at"]),
        Index(value = ["uploaded"]),
        Index(value = ["tenant_id"])
    ]
)
data class DebugJournalEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "tenant_id")
    val tenantId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "user_id")
    val userId: String?,

    @ColumnInfo(name = "document_id")
    val documentId: String?,

    @ColumnInfo(name = "stage")
    val stage: String?,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "severity")
    val severity: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "uploaded")
    val uploaded: Boolean = false,

    @ColumnInfo(name = "upload_attempts")
    val uploadAttempts: Int = 0
)
