package ua.com.programmer.pick.data.notice

import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.domain.model.NewDocumentNotice

/**
 * The cursor is the server's: echoed back as `after`, dropped when the option
 * is off or another worker signs in, so a fresh start never replays old news.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewDocumentNotifierTest {

    private val client = mockk<DeviceRestClient>()
    private val authState = MutableStateFlow<UserAuthState>(signedIn("u-1"))
    private val transport = mockk<SyncTransport> {
        every { userAuthState } returns authState
        every { requiresPolling } returns true
    }

    private fun signedIn(userId: String) =
        UserAuthState.Authenticated(userId = userId, userName = "W", role = "COLLECTOR", offlineHash = null)

    private fun result(enabled: Boolean, cursor: String?, vararg numbers: String) = Result.success(
        DeviceDto.QueueNewResult(
            enabled = enabled,
            cursor = cursor,
            documents = numbers.map { DeviceDto.QueueNewDocument(id = "id-$it", number = it, salesChannel = "") },
        )
    )

    private fun TestScope.notifier() = NewDocumentNotifier(client, transport, StandardTestDispatcher(testScheduler))

    @Test
    fun `echoes the cursor and emits new documents`() = runTest {
        coEvery { client.queueNew(null) } returns result(true, "c1")
        coEvery { client.queueNew("c1") } returns result(true, "c2", "42")
        coEvery { client.queueNew("c2") } returns result(true, "c2")
        val n = notifier()
        val seen = mutableListOf<List<NewDocumentNotice>>()
        val collector = launch { n.notices.collect { seen += it } }

        n.start()
        runCurrent()
        advanceTimeBy(15_001)
        advanceTimeBy(15_001)
        n.stop()
        collector.cancel()

        coVerifyOrder {
            client.queueNew(null)
            client.queueNew("c1")
            client.queueNew("c2")
        }
        assertEquals(listOf(listOf(NewDocumentNotice("id-42", "42", null))), seen)
    }

    @Test
    fun `option off drops the cursor`() = runTest {
        coEvery { client.queueNew(null) } returnsMany listOf(result(true, "c1"), result(true, "c3"))
        coEvery { client.queueNew("c1") } returns result(false, null)
        val n = notifier()

        n.start()
        runCurrent()
        advanceTimeBy(15_001)
        advanceTimeBy(15_001)
        n.stop()

        coVerifyOrder {
            client.queueNew(null)
            client.queueNew("c1")
            client.queueNew(null)
        }
    }

    @Test
    fun `another worker starts from scratch`() = runTest {
        coEvery { client.queueNew(null) } returns result(true, "c1")
        coEvery { client.queueNew("c1") } returns result(true, "c1")
        val n = notifier()

        n.start()
        runCurrent()
        authState.value = signedIn("u-2")
        advanceTimeBy(15_001)
        n.stop()

        coVerifyOrder {
            client.queueNew(null)
            client.queueNew(null)
        }
    }
}
