package ua.com.programmer.pick

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import ua.com.programmer.pick.core.scanner.BarcodeService
import javax.inject.Inject
import ua.com.programmer.pick.presentation.navigation.PickNavGraph
import ua.com.programmer.pick.ui.theme.PickTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var barcodeService: BarcodeService

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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (barcodeService.onHardwareKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
