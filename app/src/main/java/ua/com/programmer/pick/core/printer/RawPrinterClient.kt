package ua.com.programmer.pick.core.printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Sends raw printer-language bytes to a network label printer over a plain TCP
 * socket (the "RAW" / JetDirect port, 9100 on Xprinter and most others). There
 * is no protocol framing: whatever is written is what the printer executes.
 */
object RawPrinterClient {

    const val DEFAULT_PORT = 9100
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 2_000

    /** Opens a connection, writes [payload], closes. Throws on network failure. */
    suspend fun send(host: String, port: Int, payload: ByteArray) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }
            // Half-close so the printer sees end-of-job before the socket goes away.
            socket.shutdownOutput()
        }
    }

    /**
     * Connects and asks for the one-byte TSPL status. Returns null when the
     * printer accepted the connection but did not answer (some firmwares keep
     * the status query off the network port) — the link itself is fine then.
     * Throws when the printer cannot be reached at all.
     */
    suspend fun queryStatus(host: String, port: Int): PrinterStatus? = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(TsplBuilder.STATUS_QUERY)
                flush()
            }
            try {
                val b = socket.getInputStream().read()
                if (b < 0) null else PrinterStatus(b)
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }
}
