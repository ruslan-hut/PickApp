package ua.com.programmer.pick.core.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.di.IoDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera-based barcode scanner using CameraX and ML Kit.
 * Used as fallback when hardware scanner is not available.
 */
@Singleton
class CameraScannerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gs1Parser: GS1Parser,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ScannerManager {

    companion object {
        private const val TAG = "CameraScannerManager"
        private const val SCAN_DEBOUNCE_MS = 1000L // Prevent duplicate scans
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _scanResults = MutableSharedFlow<ScanResult>(extraBufferCapacity = 16)
    override val scanResults: SharedFlow<ScanResult> = _scanResults.asSharedFlow()

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _isAvailable.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: Flow<Boolean> = _isScanning.asStateFlow()

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private var lastScanTime = 0L
    private var lastScannedBarcode: String? = null

    init {
        checkCameraAvailability()
        initializeBarcodeScanner()
    }

    private fun checkCameraAvailability() {
        val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        _isAvailable.value = hasCamera
        Log.d(TAG, "Camera available: $hasCamera")
    }

    private fun initializeBarcodeScanner() {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417
            )
            .build()

        barcodeScanner = BarcodeScanning.getClient(options)
    }

    /**
     * Check if camera permission is granted
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start camera preview and scanning
     * @param lifecycleOwner LifecycleOwner for camera binding
     * @param previewView PreviewView to display camera preview
     */
    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (!hasCameraPermission()) {
            Log.w(TAG, "Camera permission not granted")
            scope.launch {
                _scanResults.emit(
                    ScanResult.Error(
                        message = "Camera permission not granted",
                        errorType = ScanErrorType.CAMERA_PERMISSION_DENIED
                    )
                )
            }
            return
        }

        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner, previewView)
                _isScanning.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera: ${e.message}", e)
                scope.launch {
                    _scanResults.emit(
                        ScanResult.Error(
                            message = "Failed to start camera: ${e.message}",
                            exception = e,
                            errorType = ScanErrorType.CAMERA_NOT_AVAILABLE
                        )
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // Unbind all use cases before rebinding
        provider.unbindAll()

        // Preview
        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        // Image analysis for barcode scanning
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor!!, BarcodeAnalyzer())
            }

        // Use back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            Log.d(TAG, "Camera use cases bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed: ${e.message}", e)
        }
    }

    override fun startScanning() {
        // For camera, actual start is done via startCamera()
        // This method is for interface compliance
        _isScanning.value = true
    }

    override fun stopScanning() {
        Log.d(TAG, "Stopping camera scanner")
        _isScanning.value = false
        stopCamera()
    }

    /**
     * Stop camera and release resources
     */
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera: ${e.message}")
        }
    }

    override fun release() {
        stopCamera()
        barcodeScanner?.close()
        cameraExecutor?.shutdown()
        cameraExecutor = null
    }

    /**
     * Image analyzer that processes camera frames for barcodes
     */
    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            barcodeScanner?.process(inputImage)
                ?.addOnSuccessListener { barcodes ->
                    processDetectedBarcodes(barcodes)
                }
                ?.addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed: ${e.message}")
                }
                ?.addOnCompleteListener {
                    imageProxy.close()
                }
                ?: imageProxy.close()
        }
    }

    private fun processDetectedBarcodes(barcodes: List<Barcode>) {
        if (barcodes.isEmpty()) return

        // Take the first detected barcode
        val barcode = barcodes.first()
        val rawValue = barcode.rawValue ?: return

        // Debounce - prevent scanning same barcode repeatedly
        val now = System.currentTimeMillis()
        if (rawValue == lastScannedBarcode && now - lastScanTime < SCAN_DEBOUNCE_MS) {
            return
        }

        lastScanTime = now
        lastScannedBarcode = rawValue

        Log.d(TAG, "Barcode detected: $rawValue (format: ${barcode.format})")

        scope.launch {
            try {
                val format = BarcodeFormat.fromMlKitFormat(barcode.format)

                val gs1Data = if (format.supportsGS1() && gs1Parser.isGS1Barcode(rawValue)) {
                    gs1Parser.parse(rawValue)
                } else {
                    null
                }

                val result = ScanResult.Success(
                    rawValue = rawValue,
                    format = format,
                    gs1Data = gs1Data,
                    source = ScanSource.CAMERA
                )

                _scanResults.emit(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing barcode: ${e.message}", e)
                _scanResults.emit(
                    ScanResult.Error(
                        message = "Failed to process barcode: ${e.message}",
                        exception = e,
                        errorType = ScanErrorType.PROCESSING_ERROR
                    )
                )
            }
        }
    }

    /**
     * Reset the debounce to allow scanning the same barcode again
     */
    fun resetDebounce() {
        lastScannedBarcode = null
        lastScanTime = 0L
    }
}
