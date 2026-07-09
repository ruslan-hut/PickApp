package ua.com.programmer.pick.data.remote.transport.demo

/**
 * The document flavours the offline demo serves. Both are seeded on demo login
 * and both are offered as document types, so the home screen shows one mode
 * button per variant — exactly as a real session driven by the ERP's
 * `available_document_types` would.
 */
enum class DemoVariant(
    val documentType: String,
    val documentTypeDescription: String,
) {
    /** Classic picking: one line per product, quantity scanned up to plan. */
    SHIPMENT("OUTGOING_SHIPMENT", "Відвантаження (демо)"),

    /**
     * Ukrainian e-excise: one line per stamped bottle, each carrying its unique
     * stamp code plus the shared code of its group package.
     */
    EXCISE("EXCISE", "Е-Акциз (демо)");

    companion object {
        const val LOGIN = "demo"
        const val PASSWORD = "demo"
    }
}
