package ua.com.programmer.pick

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    // The answer needs no handling: without it the notice stays in-app only.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
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

    /** Android 13+ shows no notification, the new-document one included, until the worker allows it. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (barcodeService.onHardwareKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
