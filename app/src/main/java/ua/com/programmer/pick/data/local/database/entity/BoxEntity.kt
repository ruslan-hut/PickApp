package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "boxes",
    indices = [
        Index(value = ["barcode"], unique = true),
        Index(value = ["external_id"])
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

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
)
