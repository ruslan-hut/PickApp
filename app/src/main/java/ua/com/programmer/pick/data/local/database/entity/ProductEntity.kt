package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["external_id"])
    ]
)
data class ProductEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    // ERP external_id. Populated from ProductDto.externalId on sync; used by
    // SyncOrchestrator to translate DocumentLine.product_id (which the v2
    // backend now emits as an external_id) back to the local Mongo ObjectID
    // hex used as the products primary key.
    @ColumnInfo(name = "external_id")
    val externalId: String? = null,

    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "unit")
    val unit: String,

    @ColumnInfo(name = "supports_batches")
    val supportsBatches: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long
)
