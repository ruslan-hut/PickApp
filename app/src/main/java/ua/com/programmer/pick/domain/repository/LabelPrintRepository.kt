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
     * when the printer cannot be reached.
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
