package ua.com.programmer.pick

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import ua.com.programmer.pick.core.scanner.BarcodeService
import javax.inject.Inject
import ua.com.programmer.pick.presentation.navigation.PickNavGraph
import ua.com.programmer.pick.ui.theme.PickTheme
import java.util.concurrent.TimeUnit
import kotlin.getValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var barcodeService: BarcodeService

    private var backPressedTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize barcode service to listen for hardware/camera scanners
        barcodeService.initialize()
        enableEdgeToEdge()
        setContent {
            PickTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PickNavGraph()
                }
            }
        }
    }

    /**
     * Intercepts key events to capture input from a barcode scanner.
     *
     * This method checks if the incoming [KeyEvent] is from a recognized scanner device.
     * If it is, it captures the keystrokes to assemble a barcode string.
     *
     * The logic is as follows:
     * 1. Identify if the event source is a scanner using [isScannerDevice]. If not, defer to the default system handling.
     * 2. On `ACTION_DOWN`, append the character to an internal `barcode` buffer. It also implements a timeout (60ms) to clear the buffer between separate scans, ensuring that partial scans or delayed inputs don't corrupt the next valid scan.
     * 3. On `ACTION_UP` for an `ENTER` or `TAB` key, it considers the barcode scan complete. The assembled barcode string is then passed to the [SharedViewModel.onBarcodeRead] for processing, and the buffer is cleared.
     * 4. For all events handled by the scanner logic, it returns `true` to indicate that the event has been consumed and should not be propagated further.
     *
     * @param event The [KeyEvent] to be dispatched.
     * @return `true` if the event was handled by the scanner logic, otherwise the result of `super.dispatchKeyEvent(event)`.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Delegate hardware keyboard-style scanner events to BarcodeService.
        // BarcodeService will return true if the device looks like a scanner and the event was handled.
        return when (barcodeService.onHardwareKeyEvent(event)) {
            true -> true
            else -> super.dispatchKeyEvent(event)
        }
    }
}
