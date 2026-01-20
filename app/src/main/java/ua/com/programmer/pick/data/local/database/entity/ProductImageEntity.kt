package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "product_images",
    indices = [
        Index(value = ["product_id"], unique = true)
    ]
)
data class ProductImageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "url")
    val url: String?,

    @ColumnInfo(name = "base64")
    val base64: String?
)
