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
        Index(value = ["product_code"]),
        Index(value = ["document_id", "line_key"])
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

    // The ERP's own stable line id, when it supplies one. Opaque to the device:
    // guided-task line updates address a line by it, because an addressed
    // document repeats a product across lines (one per batch). Null on
    // documents whose ERP sends no line key — those match by line_number.
    @ColumnInfo(name = "line_key")
    val lineKey: String? = null,

    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "product_code")
    val productCode: String,

    @ColumnInfo(name = "product_name")
    val productName: String,

    @ColumnInfo(name = "unit")
    val unit: String,

    @ColumnInfo(name = "volume")
    val volume: Int = 0,

    @ColumnInfo(name = "volume_unit")
    val volumeUnit: String? = null,

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

    // First entry of the line's ERP barcode list, denormalized for display the
    // same way product_code / product_name are. On e-excise documents it is the
    // unique stamp code — the only thing that tells apart lines that all carry
    // the same product name. Null when the ERP sent no per-line barcodes.
    @ColumnInfo(name = "mark_code")
    val markCode: String? = null,

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "is_dirty")
    val isDirty: Boolean = false,

    // Server-set marker that a photo exists for this line (server-authoritative).
    @ColumnInfo(name = "has_photo")
    val hasPhoto: Boolean = false,

    // Device-local cache path of the captured JPEG. Never sent to/from the
    // server and never overwritten by the sync merge.
    @ColumnInfo(name = "photo_path")
    val photoPath: String? = null,

    // Device-local flag: a captured photo is awaiting upload (drained on
    // reconnect). Local-only, never synced.
    @ColumnInfo(name = "photo_pending")
    val photoPending: Boolean = false
)
