package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "document_lines",
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
        Index(value = ["product_id"]),
        Index(value = ["product_code"])
    ]
)
data class DocumentLineEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "line_number")
    val lineNumber: Int,

    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "product_code")
    val productCode: String,

    @ColumnInfo(name = "product_name")
    val productName: String,

    @ColumnInfo(name = "unit")
    val unit: String,

    @ColumnInfo(name = "planned_quantity")
    val plannedQuantity: Double,

    @ColumnInfo(name = "actual_quantity")
    val actualQuantity: Double,

    @ColumnInfo(name = "batch_number")
    val batchNumber: String?,

    @ColumnInfo(name = "expiration_date")
    val expirationDate: Long?,

    @ColumnInfo(name = "location_id")
    val locationId: String?,

    @ColumnInfo(name = "location_path")
    val locationPath: String?,

    @ColumnInfo(name = "notes")
    val notes: String?,

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "is_dirty")
    val isDirty: Boolean = false
)
