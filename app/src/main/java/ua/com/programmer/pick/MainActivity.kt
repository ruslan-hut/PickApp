package ua.com.programmer.pick

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ua.com.programmer.pick.core.scanner.BarcodeService
import javax.inject.Inject
import ua.com.programmer.pick.presentation.navigation.PickNavGraph
import ua.com.programmer.pick.presentation.notice.NewDocumentBanner
import ua.com.programmer.pick.presentation.notice.NewDocumentNoticeViewModel
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
                    val noticeViewModel: NewDocumentNoticeViewModel = hiltViewModel()
                    val notices by noticeViewModel.shown.collectAsStateWithLifecycle()
                    Box(Modifier.fillMaxSize()) {
                        PickNavGraph()
                        NewDocumentBanner(
                            documents = notices,
                            onDismiss = noticeViewModel::dismiss,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (barcodeService.onHardwareKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
