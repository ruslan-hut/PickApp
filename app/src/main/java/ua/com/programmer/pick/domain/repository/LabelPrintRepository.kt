package ua.com.programmer.pick.domain.repository

import ua.com.programmer.pick.domain.model.LabelPrinter

/**
 * Carrier label printing. Online-only: the server fetches the carrier marking
 * and renders it for the terminal's printer; the terminal writes the bytes to
 * the printer's raw port and reports back. Nothing is queued or retried later.
 */
interface LabelPrintRepository {
    /** Printers this worker may pick, and the one picked on this terminal. */
    suspend fun printers(): Result<PrinterChoice>

    /** Picks the terminal's printer; null clears the pick. */
    suspend fun selectPrinter(printerId: String?): Result<LabelPrinter?>

    /**
     * Prints the marking of the document's waybill: the given 1-based seats,
     * or every seat when [seats] is null. Fails with [PrinterNotSelectedException]
     * when the terminal has no usable printer, [PrinterUnreachableException]
     * when the printer cannot be reached, [LabelPrintRefusedException] when the
     * job could not be prepared for a reason the worker should be told.
     */
    suspend fun printLabel(documentId: String, seats: List<Int>?): Result<LabelPrintOutcome>
}

data class PrinterChoice(val printers: List<LabelPrinter>, val selectedId: String?)

data class LabelPrintOutcome(
    val printer: LabelPrinter,
    val seats: List<Int>,
    val seatCount: Int,
    val trackingNumber: String?,
)

/** No printer picked on this terminal, or the pick is no longer usable. */
class PrinterNotSelectedException(message: String) : Exception(message)

/** The job was ready but the printer did not take it. */
class PrinterUnreachableException(val printer: LabelPrinter, cause: Throwable) :
    Exception("${printer.name} (${printer.address}): ${cause.message}", cause)

/**
 * Why no printer job came back. Mirrors the server's error codes for the
 * print-job call (docs/device-api.md), plus the terminal's own "server not
 * reachable"; each has its own localized text.
 */
enum class LabelPrintRefusal {
    /** The delivery service is down or not answering — try again later. */
    CARRIER_UNAVAILABLE,
    /** The delivery service refused the connection's API key — administrator. */
    CARRIER_NOT_CONFIGURED,
    /** The delivery service has no label for this waybill — check the waybill. */
    WAYBILL_REJECTED,
    /** The document has no waybill. */
    NO_SHIPMENT,
    /** The seat is not on the waybill — parcels and seats disagree. */
    SEAT_NOT_FOUND,
    /** The printer's settings do not fit the carrier's label — administrator. */
    PRINTER_SETUP_INVALID,
    /** The label could not be prepared on the server. */
    LABEL_RENDER_FAILED,
    /** The terminal could not reach the server. */
    SERVER_UNREACHABLE;

    companion object {
        fun fromCode(code: String?): LabelPrintRefusal? = entries.firstOrNull { it.name == code }
    }
}

/** The server (or the way to it) could not produce the printer job. */
class LabelPrintRefusedException(val reason: LabelPrintRefusal, message: String, cause: Throwable? = null) :
    Exception(message, cause)
