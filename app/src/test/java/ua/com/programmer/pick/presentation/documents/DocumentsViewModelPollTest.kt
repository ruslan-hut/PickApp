package ua.com.programmer.pick.presentation.documents

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.data.sync.SyncState

/**
 * T76b54084: with no lock held nothing polled, so a document another worker
 * took stayed on the list as free. The list's poll tick asks for a delta sync
 * at the active-poll cadence; the stale guard keeps it from stacking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentsViewModelPollTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a poll tick asks for a delta sync unless one just completed`() = runTest {
        val orchestrator = mockk<SyncOrchestrator>(relaxed = true) {
            every { syncState } returns MutableStateFlow(SyncState(isUserAuthenticated = true))
        }
        val vm = DocumentsViewModel(
            documentRepository = mockk(relaxed = true),
            syncOrchestrator = orchestrator,
            appPreferences = mockk(relaxed = true) { every { selectedDocumentType } returns flowOf(null) },
            documentTypeConfigProvider = mockk(relaxed = true) { every { configs } returns flowOf(emptyMap()) },
        )

        vm.pollDocuments()

        coVerify(exactly = 1) { orchestrator.requestDeltaSyncIfStale(DocumentsViewModel.LIST_POLL_SKIP_IF_FRESH_MS) }
    }
}
