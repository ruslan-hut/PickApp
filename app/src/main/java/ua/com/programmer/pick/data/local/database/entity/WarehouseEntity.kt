package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "warehouses",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["external_id"])
    ]
)
data class WarehouseEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    // ERP external_id. Populated from WarehouseDto.externalId on sync so the
    // app can translate v2-format cross-references (e.g. Document.warehouse_id,
    // User.warehouse_id) back to the local row id. Backfilled by the next
    // warehouse sync.
    @ColumnInfo(name = "external_id")
    val externalId: String? = null,

    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "is_addressed")
    val isAddressed: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long
)
