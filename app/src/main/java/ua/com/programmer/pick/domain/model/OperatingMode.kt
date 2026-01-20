package ua.com.programmer.pick.domain.model

/**
 * Operating mode determines which document type the user works with.
 */
enum class OperatingMode {
    RECEIPT,    // Incoming Receipt documents
    SHIPMENT,   // Outgoing Shipment documents
    INVENTORY;  // Inventory/Stocktaking documents

    fun toDocumentType(): DocumentType = when (this) {
        RECEIPT -> DocumentType.INCOMING_RECEIPT
        SHIPMENT -> DocumentType.OUTGOING_SHIPMENT
        INVENTORY -> DocumentType.INVENTORY
    }

    companion object {
        fun fromDocumentType(type: DocumentType): OperatingMode = when (type) {
            DocumentType.INCOMING_RECEIPT -> RECEIPT
            DocumentType.OUTGOING_SHIPMENT -> SHIPMENT
            DocumentType.INVENTORY -> INVENTORY
        }
    }
}
