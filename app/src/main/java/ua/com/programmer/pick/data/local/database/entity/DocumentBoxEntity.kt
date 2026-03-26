package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "document_boxes",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["barcode"]),
        Index(value = ["box_id"])
    ]
)
data class DocumentBoxEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "box_id")
    val boxId: String,

    @ColumnInfo(name = "barcode")
    val barcode: String,

    @ColumnInfo(name = "weight")
    val weight: Int,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "collected_by")
    val collectedBy: String? = null,

    @ColumnInfo(name = "collected_at")
    val collectedAt: Long? = null,

    @ColumnInfo(name = "picked_up_by")
    val pickedUpBy: String? = null,

    @ColumnInfo(name = "picked_up_at")
    val pickedUpAt: Long? = null,

    @ColumnInfo(name = "delivered_by")
    val deliveredBy: String? = null,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    @ColumnInfo(name = "version")
    val version: Int
)
