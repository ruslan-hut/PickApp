package ua.com.programmer.pick.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Scan codes that belong to a document line rather than to a product.
 *
 * The ERP supplies them per line. A code may be shared by several lines of the
 * same document — that is how a group-package barcode works: scanning it closes
 * every line packed inside. Introduced for the Ukrainian e-excise (Е-Акциз)
 * flow, where a line is one stamped unit carrying its unique stamp code plus
 * the code of the box it travels in.
 *
 * Rows are replaced wholesale on every document sync; they are ERP-owned and
 * never edited on the device.
 */
@Entity(
    tableName = "document_line_barcodes",
    primaryKeys = ["line_id", "barcode"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["line_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["barcode"])]
)
data class DocumentLineBarcodeEntity(
    @ColumnInfo(name = "line_id")
    val lineId: String,

    @ColumnInfo(name = "barcode")
    val barcode: String
)
