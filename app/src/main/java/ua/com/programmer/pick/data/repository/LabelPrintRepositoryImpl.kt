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
import ua.com.programmer.pick.domain.repository.LabelPrintRefusal
import ua.com.programmer.pick.domain.repository.LabelPrintRefusedException
import ua.com.programmer.pick.domain.repository.LabelPrintRepository
import ua.com.programmer.pick.domain.repository.PrinterChoice
import ua.com.programmer.pick.domain.repository.PrinterNotSelectedException
import ua.com.programmer.pick.domain.repository.PrinterUnreachableException
import java.io.IOException
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
                AppLog.w(TAG, "print job for $documentId refused: ${(e as? DeviceApiException)?.code} ${e.message}")
                return@withContext Result.failure(jobFailure(e))
            }
            val printer = job.printer.toDomain()
            val bytes = Base64.decode(job.data, Base64.DEFAULT)

            val sent = sendWithRetry(printer, bytes)
            report(documentId, seats, printer, sent.exceptionOrNull())
            sent.map { LabelPrintOutcome(printer, job.seats, job.seatCount, job.trackingNumber) }
                .recoverCatching { throw PrinterUnreachableException(printer, it) }
        }

    /** Turns a failed print-job call into what the worker is told. */
    private fun jobFailure(e: Throwable): Throwable {
        if (e is IOException) {
            return LabelPrintRefusedException(LabelPrintRefusal.SERVER_UNREACHABLE, e.message.orEmpty(), e)
        }
        if (e !is DeviceApiException) return e
        if (e.code == PRINTER_NOT_SELECTED) return PrinterNotSelectedException(e.message.orEmpty())
        val reason = LabelPrintRefusal.fromCode(e.code) ?: return e
        return LabelPrintRefusedException(reason, e.message.orEmpty(), e)
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
