package ua.com.programmer.pick.data.remote.websocket

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.util.Clock
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.core.util.NetworkMonitor
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the WebSocket half-open watchdog. The manager's
 * timing now runs off an injectable [Clock] backed by the test scheduler's
 * virtual time, so every PING/PONG and stale-socket window is deterministic —
 * no real `delay()` or wall-clock sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketManagerTest {

    // Non-zero base so the very first escalateStaleSocket() at virtual t=0 is
    // not swallowed by the `now - lastStaleEscalationAt < window` debounce
    // guard (lastStaleEscalationAt starts at 0).
    private val clockBase = 1_000_000L
    private val healthCheckTimeoutMs = 7_000L

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val clock = Clock { clockBase + scheduler.currentTime }

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var builder: OkHttpClient.Builder
    private lateinit var messageParser: MessageParser
    private lateinit var appPreferences: AppPreferences
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var debugJournal: DebugJournal

    private val createdSockets = mutableListOf<FakeWebSocket>()
    private val capturedListeners = mutableListOf<WebSocketListener>()

    // Flips what every fake socket returns from send(): false simulates a
    // wedged half-open socket.
    private var sendResult = true

    private lateinit var manager: WebSocketManager

    @Before
    fun setUp() {
        okHttpClient = mockk()
        builder = mockk()
        every { okHttpClient.newBuilder() } returns builder
        every { builder.pingInterval(any<Long>(), any<TimeUnit>()) } returns builder
        every { builder.build() } returns okHttpClient
        every { okHttpClient.newWebSocket(any(), any()) } answers {
            capturedListeners.add(secondArg())
            FakeWebSocket { sendResult }.also { createdSockets.add(it) }
        }

        messageParser = mockk(relaxed = true)
        every { messageParser.generateMessageId() } returns "msg-id"
        every { messageParser.getCurrentTimestamp() } returns "ts"
        every { messageParser.serializeMessage(any()) } returns "{}"

        appPreferences = mockk(relaxed = true)
        every { appPreferences.getDeviceIdSync() } returns "device-1"
        every { appPreferences.getUserCredentialsSync() } returns null

        networkMonitor = mockk(relaxed = true)
        every { networkMonitor.isCurrentlyConnected() } returns true

        debugJournal = mockk(relaxed = true)

        manager = WebSocketManager(
            okHttpClient = okHttpClient,
            messageParser = messageParser,
            appPreferences = appPreferences,
            networkMonitor = networkMonitor,
            debugJournal = debugJournal,
            clock = clock,
            ioDispatcher = dispatcher
        )
    }

    @Test
    fun `burst of failed sends triggers a single forced reconnect`() = runTest(dispatcher) {
        sendResult = false // every send fails — wedged half-open socket
        connectAndOpen()
        assertEquals(1, createdSockets.size)
        assertTrue(manager.isConnected())

        // Three failed sends at the same virtual instant. Only the first should
        // escalate; the debounce window swallows the rest.
        repeat(3) { manager.sendMessage(ping()) }

        // Run the queued forceReconnect without advancing time, so the 30s ping
        // loop can't fire and inflate the socket count.
        runCurrent()

        assertEquals(2, createdSockets.size) // original + exactly one replacement
        assertTrue("old socket must be hard-cancelled", createdSockets[0].cancelled)
    }

    @Test
    fun `stale escalation re-arms after the debounce window elapses`() = runTest(dispatcher) {
        sendResult = false
        connectAndOpen()

        manager.sendMessage(ping()) // escalation #1 at t=0
        runCurrent()
        assertEquals(2, createdSockets.size)

        // Re-open the replacement socket so we're Connected again.
        capturedListeners.last().onOpen(createdSockets.last(), switchingProtocolsResponse())
        assertTrue(manager.isConnected())

        // Still inside the window → guarded, no new socket.
        manager.sendMessage(ping())
        runCurrent()
        assertEquals(2, createdSockets.size)

        // Past the window → escalation re-arms.
        advanceTimeBy(healthCheckTimeoutMs + 1)
        manager.sendMessage(ping())
        runCurrent()
        assertEquals(3, createdSockets.size)
    }

    @Test
    fun `verifyConnectionHealth forces reconnect when probe gets no pong`() = runTest(dispatcher) {
        sendResult = true // sends succeed; only the missing pong drives recovery
        connectAndOpen()
        assertEquals(1, createdSockets.size)

        manager.verifyConnectionHealth()
        // No PONG ever arrives — let the probe deadline lapse.
        advanceTimeBy(healthCheckTimeoutMs + 500)
        runCurrent()

        assertEquals(2, createdSockets.size)
    }

    @Test
    fun `verifyConnectionHealth keeps socket when a pong answers the probe`() = runTest(dispatcher) {
        sendResult = true
        every { messageParser.parseMessage(any()) } returns
            SyncMessage.Pong(id = "p", timestamp = "t")
        connectAndOpen()
        assertEquals(1, createdSockets.size)

        manager.verifyConnectionHealth()
        runCurrent() // probe sends its PING and suspends on the first poll delay

        // Advance one poll tick so the PONG lands at a strictly later instant
        // than the probe's captured baseline, then deliver it.
        advanceTimeBy(200)
        capturedListeners.last().onMessage(createdSockets.last(), "{pong}")
        runCurrent()

        advanceTimeBy(400)
        runCurrent()

        assertEquals(1, createdSockets.size) // probe satisfied — no reconnect
        assertTrue(manager.isConnected())
    }

    @Test
    fun `verifyConnectionHealth connects when disconnected`() = runTest(dispatcher) {
        assertEquals(0, createdSockets.size)

        manager.verifyConnectionHealth() // state is Disconnected
        runCurrent()

        assertEquals(1, createdSockets.size)
    }

    @Test
    fun `missing pong after a ping interval forces reconnect`() = runTest(dispatcher) {
        sendResult = true
        connectAndOpen()
        assertEquals(1, createdSockets.size)

        // One full ping interval → a PING is sent and the pong-timeout armed.
        advanceTimeBy(Constants.Network.WEBSOCKET_PING_INTERVAL_SECONDS * 1000 + 100)
        runCurrent()
        // Pong-timeout window lapses with no PONG → escalate to reconnect.
        advanceTimeBy(Constants.Network.WEBSOCKET_PONG_TIMEOUT_SECONDS * 1000 + 100)
        runCurrent()

        assertEquals(2, createdSockets.size)
    }

    // ---- helpers ----

    private fun connectAndOpen() {
        manager.connect()
        capturedListeners.last().onOpen(createdSockets.last(), switchingProtocolsResponse())
    }

    private fun ping() = SyncMessage.Ping(id = "p", timestamp = "t")

    private fun switchingProtocolsResponse(): Response = Response.Builder()
        .request(Request.Builder().url("https://localhost/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    private class FakeWebSocket(private val sendResult: () -> Boolean) : WebSocket {
        var cancelled = false
        var closed = false
        val sentText = mutableListOf<String>()

        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            sentText.add(text)
            return sendResult()
        }
        override fun send(bytes: ByteString): Boolean = sendResult()
        override fun close(code: Int, reason: String?): Boolean {
            closed = true
            return true
        }
        override fun cancel() {
            cancelled = true
        }
    }
}
