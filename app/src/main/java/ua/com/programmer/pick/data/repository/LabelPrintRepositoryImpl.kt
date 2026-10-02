package ua.com.programmer.pick.data.repository

import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.printer.RawPrinterClient
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.remote.api.DeviceApiException
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.domain.model.LabelPrinter
import ua.com.programmer.pick.domain.repository.LabelPrintOutcome
import ua.com.programmer.pick.domain.repository.LabelPrintRepository
import ua.com.programmer.pick.domain.repository.PrinterChoice
import ua.com.programmer.pick.domain.repository.PrinterNotSelectedException
import ua.com.programmer.pick.domain.repository.PrinterUnreachableException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the REST client directly rather than the sync transport: printing is
 * an online action with nothing to queue. The printer job is opaque bytes —
 * the server owns the label format — written to the printer's raw TCP port.
 */
@Singleton
class LabelPrintRepositoryImpl @Inject constructor(
    private val client: DeviceRestClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LabelPrintRepository {

    private companion object {
        const val TAG = "LabelPrint"
        const val PRINTER_NOT_SELECTED = "PRINTER_NOT_SELECTED"
        const val RETRY_DELAY_MS = 1_000L
    }

    override suspend fun printers(): Result<PrinterChoice> = withContext(ioDispatcher) {
        client.printers().map { r -> PrinterChoice(r.printers.map { it.toDomain() }, r.selectedId) }
    }

    override suspend fun selectPrinter(printerId: String?): Result<LabelPrinter?> = withContext(ioDispatcher) {
        client.selectPrinter(printerId.orEmpty()).map { it.printer?.toDomain() }
    }

    override suspend fun printLabel(documentId: String, seats: List<Int>?): Result<LabelPrintOutcome> =
        withContext(ioDispatcher) {
            val job = client.shipmentPrintJob(documentId, seats).getOrElse { e ->
                if (e is DeviceApiException && e.code == PRINTER_NOT_SELECTED) {
                    return@withContext Result.failure(PrinterNotSelectedException(e.message.orEmpty()))
                }
                return@withContext Result.failure(e)
            }
            val printer = job.printer.toDomain()
            val bytes = Base64.decode(job.data, Base64.DEFAULT)

            val sent = sendWithRetry(printer, bytes)
            report(documentId, seats, printer, sent.exceptionOrNull())
            sent.map { LabelPrintOutcome(printer, job.seats, job.seatCount, job.trackingNumber) }
                .recoverCatching { throw PrinterUnreachableException(printer, it) }
        }

    /** One retry after a short pause: a printer waking up or a Wi-Fi hiccup. */
    private suspend fun sendWithRetry(printer: LabelPrinter, bytes: ByteArray): Result<Unit> {
        val first = runCatching { RawPrinterClient.send(printer.host, printer.port, bytes) }
        if (first.isSuccess) return first
        AppLog.w(TAG, "send to ${printer.address} failed, retrying: ${first.exceptionOrNull()?.message}")
        delay(RETRY_DELAY_MS)
        return runCatching { RawPrinterClient.send(printer.host, printer.port, bytes) }
    }

    /** Best effort: the label is out (or not) whatever the history says. */
    private suspend fun report(documentId: String, seats: List<Int>?, printer: LabelPrinter, error: Throwable?) {
        val request = DeviceDto.PrintedRequest(
            seats = seats,
            ok = error == null,
            error = error?.let { it.message ?: it.javaClass.simpleName },
            printerId = printer.id,
        )
        client.shipmentPrinted(documentId, request).onFailure {
            AppLog.w(TAG, "print report for $documentId not delivered: ${it.message}")
        }
    }

    private fun DeviceDto.PrinterDto.toDomain() =
        LabelPrinter(id, name, host, port, labelWidthMm, labelHeightMm)
}
