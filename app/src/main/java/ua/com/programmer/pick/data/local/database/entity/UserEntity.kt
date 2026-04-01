package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "login")
    val login: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "password_hash")
    val passwordHash: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "last_login_at")
    val lastLoginAt: Long? = null,

    // TODO(legacy): remove column after all clients migrate to server-driven document types.
    //  Kept for Room schema compatibility — not read or written by current code.
    @ColumnInfo(name = "operating_mode", defaultValue = "RECEIPT")
    val operatingMode: String = "RECEIPT",

    @ColumnInfo(name = "warehouse_id")
    val warehouseId: String? = null,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long
)
