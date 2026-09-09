package ua.com.programmer.pick.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.data.remote.transport.MessageParser
import ua.com.programmer.pick.data.remote.transport.SyncMessage
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.domain.model.TaskState
import ua.com.programmer.pick.domain.repository.TaskCallResult

/**
 * The repository's job beyond mapping is idempotency: a retry must repeat the
 * *same* `operation_id` so the server replays rather than re-applies, and a
 * server verdict must not be retried at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidedTaskRepositoryImplTest {

    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    private lateinit var transport: SyncTransport
    private lateinit var authState: MutableStateFlow<UserAuthState>
    private lateinit var repository: GuidedTaskRepositoryImpl

    @Before
    fun setUp() {
        authState = MutableStateFlow(UserAuthState.NotAuthenticated)
        transport = mockk(relaxed = true) {
            every { userAuthState } returns authState
        }
        val messageParser = mockk<MessageParser>(relaxed = true) {
            every { generateMessageId() } returns "msg-1"
            every { getCurrentTimestamp() } returns "2026-09-09T00:00:00Z"
        }
        repository = GuidedTaskRepositoryImpl(
            transport = transport,
            messageParser = messageParser,
            debugJournal = mockk<DebugJournal>(relaxed = true),
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `retries a transport failure with the same operation id`() = runTest {
        val sent = mutableListOf<SyncMessage>()
        var call = 0
        coEvery {
            transport.sendAndAwait(capture(sent), SyncMessage.TaskResult::class.java, any())
        } answers {
            call++
            // Two silent failures, then the server answers.
            if (call < 3) null else taskResult("rc_count")
        }

        val result = repository.act(
            taskId = "t1",
            stepId = "rc_count",
            action = "confirm",
            quantity = 45,
            operationId = "op-fixed",
        )

        assertTrue(result is TaskCallResult.Success)
        assertEquals(3, sent.size)
        val ids = sent.map { (it as SyncMessage.TaskAction).operationId }.toSet()
        assertEquals(setOf("op-fixed"), ids)
    }

    @Test
    fun `does not retry a server error envelope`() = runTest {
        var calls = 0
        coEvery {
            transport.sendAndAwait(any(), SyncMessage.TaskResult::class.java, any())
        } answers {
            calls++
            SyncMessage.TaskResult(
                id = "r", timestamp = "t", success = false,
                errorCode = "LOCKED", errorMessage = "Ivan is counting this cell",
            )
        }

        val result = repository.act("t1", "rc_count", "scan", value = "A-01-02")

        assertEquals(1, calls)
        val failure = result as TaskCallResult.Failure
        assertEquals("LOCKED", failure.code)
        assertEquals("Ivan is counting this cell", failure.message)
    }

    @Test
    fun `mints a fresh operation id per action when none is given`() = runTest {
        val sent = slot<SyncMessage>()
        coEvery {
            transport.sendAndAwait(capture(sent), SyncMessage.TaskResult::class.java, any())
        } returns taskResult("rc_count")

        repository.act("t1", "rc_count", "scan", value = "A")
        val first = (sent.captured as SyncMessage.TaskAction).operationId
        repository.act("t1", "rc_count", "scan", value = "B")
        val second = (sent.captured as SyncMessage.TaskAction).operationId

        assertTrue(first.isNotBlank())
        assertTrue(first != second)
    }

    @Test
    fun `a finished task drops out of the open list`() = runTest {
        seedOpenTask("t1")
        coEvery {
            transport.sendAndAwait(any(), SyncMessage.TaskResult::class.java, any())
        } returns taskResult("rc_done", state = "DONE")

        val result = repository.act("t1", "rc_count", "confirm", quantity = 45)

        assertEquals(TaskState.DONE, result.taskOrNull?.state)
        assertTrue(repository.openTasks.value.isEmpty())
    }

    @Test
    fun `an open task stays in the list`() = runTest {
        seedOpenTask("t1")
        coEvery {
            transport.sendAndAwait(any(), SyncMessage.TaskResult::class.java, any())
        } returns taskResult("rc_count")

        repository.act("t1", "rc_count", "scan", value = "A")

        assertEquals(listOf("t1"), repository.openTasks.value.map { it.id })
    }

    @Test
    fun `login seeds the open task list and logout clears it`() = runTest {
        authState.value = UserAuthState.Authenticated(
            userId = "u", userName = "W", role = "COLLECTOR", offlineHash = null,
            openTasks = listOf(
                DeviceDto.OpenTask(id = "t9", type = "CELL_RECOUNT", stepTitle = "A-01-02"),
            ),
        )
        assertEquals(listOf("t9"), repository.openTasks.value.map { it.id })
        assertEquals("A-01-02", repository.openTasks.value.single().stepTitle)

        authState.value = UserAuthState.NotAuthenticated
        assertTrue(repository.openTasks.value.isEmpty())
        assertNull(repository.active.value)
    }

    @Test
    fun `refreshOpen replaces the list from full envelopes`() = runTest {
        coEvery {
            transport.sendAndAwait(any(), SyncMessage.TaskOpenResult::class.java, any())
        } returns SyncMessage.TaskOpenResult(
            id = "r", timestamp = "t", success = true,
            tasks = listOf(
                DeviceDto.TaskResponse(
                    task = DeviceDto.Task(id = "t2", type = "PLACEMENT", state = "OPEN", startedAt = 5),
                    step = DeviceDto.TaskStep(id = "pl_scan", title = "Place it"),
                ),
            ),
        )

        assertTrue(repository.refreshOpen())
        val task = repository.openTasks.value.single()
        assertEquals("t2", task.id)
        assertEquals("Place it", task.stepTitle)
        assertEquals(5L, task.startedAt)
    }

    @Test
    fun `NOT_FOUND drops the task from the unfinished list`() = runTest {
        seedOpenTask("t1")
        coEvery {
            transport.sendAndAwait(any(), SyncMessage.TaskResult::class.java, any())
        } returns SyncMessage.TaskResult(
            id = "r", timestamp = "t", success = false,
            errorCode = "NOT_FOUND", errorMessage = "task not found",
        )

        val result = repository.get("t1")

        assertTrue(result is TaskCallResult.Failure)
        // Continue must not lead back to a task an admin cancelled and purged.
        assertTrue(repository.openTasks.value.isEmpty())
    }

    @Test
    fun `another server code leaves the unfinished list alone`() = runTest {
        seedOpenTask("t1")
        coEvery {
            transport.sendAndAwait(any(), SyncMessage.TaskResult::class.java, any())
        } returns SyncMessage.TaskResult(
            id = "r", timestamp = "t", success = false,
            errorCode = "LOCKED", errorMessage = "held by someone else",
        )

        repository.act("t1", "rc_count", "scan", value = "A")

        assertEquals(listOf("t1"), repository.openTasks.value.map { it.id })
    }

    /** The login response is the only production seed for the open-task list. */
    private fun seedOpenTask(id: String) {
        authState.value = UserAuthState.Authenticated(
            userId = "u", userName = "W", role = "COLLECTOR", offlineHash = null,
            openTasks = listOf(DeviceDto.OpenTask(id = id, type = "CELL_RECOUNT")),
        )
    }

    private fun taskResult(stepId: String, state: String = "OPEN") = SyncMessage.TaskResult(
        id = "r",
        timestamp = "t",
        success = true,
        response = DeviceDto.TaskResponse(
            task = DeviceDto.Task(id = "t1", type = "CELL_RECOUNT", state = state, stepId = stepId),
            step = DeviceDto.TaskStep(id = stepId, title = "Cell A-01-02", expect = "qty"),
        ),
    )
}
