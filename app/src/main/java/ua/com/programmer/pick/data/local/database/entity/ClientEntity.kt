package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "clients",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["external_id"])
    ]
)
data class ClientEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    // ERP external_id. Populated from ClientDto.externalId on sync so the app
    // can translate v2-format cross-references (e.g. Document.client_id) back
    // to the local row id. Backfilled by the next client sync.
    @ColumnInfo(name = "external_id")
    val externalId: String? = null,

    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "address")
    val address: String? = null,

    @ColumnInfo(name = "phone")
    val phone: String? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long
)
