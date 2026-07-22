package ua.com.programmer.pick.presentation.document

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.core.scanner.BarcodeFormat
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.scanner.GS1Data
import ua.com.programmer.pick.core.scanner.ScannedBarcode
import ua.com.programmer.pick.data.repository.DocumentTypeConfigProvider
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.domain.model.Product
import ua.com.programmer.pick.domain.repository.ProductRepository

/**
 * Targeted tests for the on-demand barcode resolution path
 * ([DocumentDetailViewModel.resolveScannedProduct]) that keeps a scan from
 * being mislabelled "not in this document" when the product's barcode rows
 * simply haven't synced to the local catalogue yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailViewModelResolveScannedProductTest {

    // A dispatcher on its OWN scheduler backs Dispatchers.Main so the VM's
    // init{} flow collectors (which suspend forever on empty flows) never land
    // on the runTest scheduler and trip its uncompleted-coroutine check.
    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var productRepository: ProductRepository
    private lateinit var syncOrchestrator: SyncOrchestrator

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): DocumentDetailViewModel {
        productRepository = mockk()
        syncOrchestrator = mockk(relaxed = true) {
            every { docSyncEvents } returns MutableSharedFlow()
        }
        val barcodeService = mockk<BarcodeService>(relaxed = true) {
            every { scannedBarcodes } returns MutableSharedFlow()
        }
        val documentTypeConfigProvider = mockk<DocumentTypeConfigProvider>(relaxed = true) {
            every { configs } returns flowOf(emptyMap())
        }
        return DocumentDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            documentRepository = mockk(relaxed = true),
            productImageDao = mockk(relaxed = true),
            barcodeService = barcodeService,
            productRepository = productRepository,
            syncOrchestrator = syncOrchestrator,
            debugJournal = mockk(relaxed = true),
            boxDao = mockk(relaxed = true),
            documentBoxDao = mockk(relaxed = true),
            documentTypeConfigProvider = documentTypeConfigProvider,
            imageCompressor = mockk(relaxed = true),
            linePhotoStore = mockk(relaxed = true),
            ioDispatcher = mainDispatcher
        )
    }

    private fun product(id: String) =
        Product(id = id, code = "code-$id", name = "name-$id", unit = "шт")

    private fun scan(raw: String, gtin: String? = null) = ScannedBarcode(
        rawValue = raw,
        format = BarcodeFormat.EAN_13,
        gs1Data = gtin?.let { GS1Data(gtin = it) }
    )

    @Test
    fun `local cache hit returns product id without a server lookup`() = runTest {
        val vm = buildViewModel()
        coEvery { productRepository.getProductByBarcode("BC1") } returns product("P1")

        val resolved = vm.resolveScannedProduct(scan("BC1"))

        assertEquals("P1", resolved)
        coVerify(exactly = 0) { syncOrchestrator.lookupProductByBarcode(any()) }
    }

    @Test
    fun `cache miss then successful server lookup resolves via re-read`() = runTest {
        val vm = buildViewModel()
        // First read misses (not synced yet); after the lookup persists it, the
        // second read hits.
        coEvery { productRepository.getProductByBarcode("BC2") } returnsMany
            listOf(null, product("P2"))
        coEvery { syncOrchestrator.lookupProductByBarcode("BC2") } returns true

        val resolved = vm.resolveScannedProduct(scan("BC2"))

        assertEquals("P2", resolved)
        coVerify(exactly = 1) { syncOrchestrator.lookupProductByBarcode("BC2") }
        coVerify(exactly = 2) { productRepository.getProductByBarcode("BC2") }
    }

    @Test
    fun `cache miss and failed server lookup returns null without a second read`() = runTest {
        val vm = buildViewModel()
        coEvery { productRepository.getProductByBarcode("BC3") } returns null
        coEvery { syncOrchestrator.lookupProductByBarcode("BC3") } returns false

        val resolved = vm.resolveScannedProduct(scan("BC3"))

        assertNull(resolved)
        // No re-read once the lookup fails.
        coVerify(exactly = 1) { productRepository.getProductByBarcode("BC3") }
    }

    @Test
    fun `blank barcode returns null without touching the repository or server`() = runTest {
        val vm = buildViewModel()

        val resolved = vm.resolveScannedProduct(scan(""))

        assertNull(resolved)
        coVerify(exactly = 0) { productRepository.getProductByBarcode(any()) }
        coVerify(exactly = 0) { syncOrchestrator.lookupProductByBarcode(any()) }
    }

    @Test
    fun `gs1 product code is preferred over the raw scan as the lookup key`() = runTest {
        val vm = buildViewModel()
        coEvery { productRepository.getProductByBarcode("4600000000001") } returns product("P5")

        val resolved = vm.resolveScannedProduct(scan(raw = "]d2raw-envelope", gtin = "4600000000001"))

        assertEquals("P5", resolved)
    }
}
