package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "boxes",
    indices = [
        Index(value = ["barcode"], unique = true),
        Index(value = ["external_id"]),
        Index(value = ["is_parcel"])
    ]
)
data class BoxEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "external_id")
    val externalId: String?,

    @ColumnInfo(name = "barcode")
    val barcode: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "length")
    val length: Int,

    @ColumnInfo(name = "width")
    val width: Int,

    @ColumnInfo(name = "height")
    val height: Int,

    // Declared by ERP via `is_parcel`. Parcels count as delivery places;
    // packages nest inside a parcel during pack.
    @ColumnInfo(name = "is_parcel", defaultValue = "0")
    val isParcel: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
)
