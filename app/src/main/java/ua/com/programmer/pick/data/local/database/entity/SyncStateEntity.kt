package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "last_sync_time")
    val lastSyncTime: Long = 0L,

    @ColumnInfo(name = "cursor")
    val cursor: String? = null,  // ISO 8601 timestamp cursor for delta sync

    @ColumnInfo(name = "status")
    val status: String = "IDLE",

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
