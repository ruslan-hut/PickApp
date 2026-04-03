package ua.com.programmer.pick.core.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ua.com.programmer.pick.BuildConfig
import ua.com.programmer.pick.core.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DataWedgeStatus(
    val isQuerying: Boolean = false,
    val isZebraDevice: Boolean = false,
    val activeProfile: String? = null,
    val profileEnabled: Boolean? = null,
    val scannerEnabled: Boolean? = null,
    val intentOutputEnabled: Boolean? = null,
    val intentAction: String? = null,
    val intentDelivery: String? = null,
    val keystrokeOutputEnabled: Boolean? = null,
    val datawedgeVersion: String? = null,
    val errorMessage: String? = null,
    val queryTimestamp: Long = 0
)

@Singleton
class DataWedgeDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DataWedgeDiagnostics"
        private const val DW_ACTION = "com.symbol.datawedge.api.ACTION"
        private const val DW_RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"
        private const val DW_PACKAGE = "com.symbol.datawedge"
        private const val DW_NOTIFICATION_ACTION = "com.symbol.datawedge.api.NOTIFICATION_ACTION"
        private const val QUERY_TIMEOUT_MS = 3000L
        private const val EXPECTED_INTENT_ACTION = "ua.com.programmer.pick.SCAN"
    }

    private val _status = MutableStateFlow(DataWedgeStatus())
    val status: StateFlow<DataWedgeStatus> = _status.asStateFlow()

    private var receiverRegistered = false
    private var queryStartTime = 0L

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            AppLog.d(TAG, "Received: ${intent.action}")

            when {
                intent.hasExtra("com.symbol.datawedge.api.RESULT_GET_VERSION_INFO") -> {
                    handleVersionInfo(intent)
                }
                intent.hasExtra("com.symbol.datawedge.api.RESULT_GET_ACTIVE_PROFILE") -> {
                    handleActiveProfile(intent)
                }
                intent.hasExtra("com.symbol.datawedge.api.RESULT_GET_CONFIG") -> {
                    handleGetConfig(intent)
                }
            }
        }
    }

    fun queryDataWedge() {
        val isZebra = Build.MANUFACTURER.lowercase().let {
            it.contains("zebra") || it.contains("symbol")
        }

        _status.update {
            DataWedgeStatus(
                isQuerying = true,
                isZebraDevice = isZebra,
                queryTimestamp = System.currentTimeMillis()
            )
        }

        if (!isZebra) {
            _status.update {
                it.copy(
                    isQuerying = false,
                    errorMessage = "Not a Zebra device (manufacturer: ${Build.MANUFACTURER})"
                )
            }
            return
        }

        queryStartTime = System.currentTimeMillis()
        registerReceiver()

        // Query version info
        sendDwIntent("com.symbol.datawedge.api.GET_VERSION_INFO", "")

        // Query active profile
        sendDwIntent("com.symbol.datawedge.api.GET_ACTIVE_PROFILE", "")
    }

    fun exportAsText(): String = buildString {
        val status = _status.value
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        appendLine("=== DataWedge Diagnostics Report ===")
        appendLine()

        appendLine("[Device]")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("App: ${BuildConfig.APPLICATION_ID}")
        appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        appendLine()

        appendLine("[DataWedge]")
        appendLine("Zebra device: ${status.isZebraDevice}")
        appendLine("DataWedge version: ${status.datawedgeVersion ?: "unknown"}")
        appendLine("Query time: ${if (status.queryTimestamp > 0) formatter.format(Date(status.queryTimestamp)) else "never"}")
        appendLine()

        appendLine("[Active Profile]")
        appendLine("Name: ${status.activeProfile ?: "unknown"}")
        appendLine("Enabled: ${status.profileEnabled ?: "unknown"}")
        appendLine("Scanner input: ${status.scannerEnabled ?: "unknown"}")
        appendLine()

        appendLine("[Intent Output]")
        appendLine("Enabled: ${status.intentOutputEnabled ?: "unknown"}")
        appendLine("Action: ${status.intentAction ?: "unknown"}")
        appendLine("Delivery: ${status.intentDelivery ?: "unknown"}")
        appendLine("Expected action: $EXPECTED_INTENT_ACTION")
        val actionMatch = status.intentAction?.let { it == EXPECTED_INTENT_ACTION }
        appendLine("Action matches: ${actionMatch ?: "unknown"}")
        appendLine()

        appendLine("[Keystroke Output]")
        appendLine("Enabled: ${status.keystrokeOutputEnabled ?: "unknown"}")
        appendLine()

        if (status.errorMessage != null) {
            appendLine("[Errors]")
            appendLine(status.errorMessage)
            appendLine()
        }

        appendLine("[Recommendation]")
        when {
            !status.isZebraDevice -> appendLine("This is not a Zebra device. DataWedge diagnostics are not applicable.")
            status.activeProfile == null -> appendLine("Could not query DataWedge. Ensure DataWedge is installed and running.")
            status.intentOutputEnabled != true -> appendLine("Intent Output is DISABLED. Enable it in DataWedge profile settings.")
            status.intentAction != EXPECTED_INTENT_ACTION -> appendLine("Intent action mismatch! Set action to: $EXPECTED_INTENT_ACTION")
            status.intentDelivery?.lowercase()?.contains("broadcast") != true -> appendLine("Intent delivery should be 'Broadcast intent', not '${status.intentDelivery}'.")
            else -> appendLine("Configuration looks correct. If scanning still fails, check that Scanner Input is enabled.")
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(DW_RESULT_ACTION)
            addAction(DW_NOTIFICATION_ACTION)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        ContextCompat.registerReceiver(
            context,
            resultReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(resultReceiver)
        } catch (_: Exception) {}
        receiverRegistered = false
    }

    private fun sendDwIntent(extraKey: String, extraValue: String) {
        try {
            val intent = Intent(DW_ACTION)
            intent.setPackage(DW_PACKAGE)
            intent.putExtra(extraKey, extraValue)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to send DataWedge intent: ${e.message}", e)
            _status.update {
                it.copy(
                    isQuerying = false,
                    errorMessage = "Failed to communicate with DataWedge: ${e.message}"
                )
            }
        }
    }

    private fun sendDwIntent(extraKey: String, extraValue: Bundle) {
        try {
            val intent = Intent(DW_ACTION)
            intent.setPackage(DW_PACKAGE)
            intent.putExtra(extraKey, extraValue)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to send DataWedge intent: ${e.message}", e)
        }
    }

    private fun handleVersionInfo(intent: Intent) {
        val version = intent.getStringExtra("com.symbol.datawedge.api.RESULT_GET_VERSION_INFO")
            ?: intent.getBundleExtra("com.symbol.datawedge.api.RESULT_GET_VERSION_INFO")
                ?.getString("DATAWEDGE")
        AppLog.d(TAG, "DataWedge version: $version")
        _status.update { it.copy(datawedgeVersion = version) }
    }

    private fun handleActiveProfile(intent: Intent) {
        val profile = intent.getStringExtra("com.symbol.datawedge.api.RESULT_GET_ACTIVE_PROFILE")
        AppLog.d(TAG, "Active profile: $profile")
        _status.update { it.copy(activeProfile = profile ?: "none") }

        // Now query config for this profile
        if (profile != null) {
            queryProfileConfig(profile)
        } else {
            _status.update { it.copy(isQuerying = false) }
        }
    }

    private fun queryProfileConfig(profileName: String) {
        val configBundle = Bundle().apply {
            putString("PROFILE_NAME", profileName)
            putBundle("PLUGIN_CONFIG", Bundle().apply {
                putStringArrayList("PLUGIN_NAME", arrayListOf("BARCODE", "INTENT", "KEYSTROKE"))
            })
        }
        sendDwIntent("com.symbol.datawedge.api.GET_CONFIG", configBundle)
    }

    @Suppress("DEPRECATION")
    private fun handleGetConfig(intent: Intent) {
        val configBundle = intent.getBundleExtra("com.symbol.datawedge.api.RESULT_GET_CONFIG")
        if (configBundle == null) {
            AppLog.w(TAG, "GET_CONFIG result is null")
            _status.update {
                it.copy(isQuerying = false, errorMessage = "DataWedge returned empty config")
            }
            return
        }

        AppLog.d(TAG, "Config bundle keys: ${configBundle.keySet()}")

        val profileEnabled = configBundle.getString("PROFILE_ENABLED")

        val pluginConfigs = configBundle.getParcelableArrayList<Bundle>("PLUGIN_CONFIG")
        AppLog.d(TAG, "Plugin configs count: ${pluginConfigs?.size}")

        var scannerEnabled: Boolean? = null
        var intentEnabled: Boolean? = null
        var intentAction: String? = null
        var intentDelivery: String? = null
        var keystrokeEnabled: Boolean? = null

        pluginConfigs?.forEach { plugin ->
            val pluginName = plugin.getString("PLUGIN_NAME")?.uppercase()
            val paramList = plugin.getBundle("PARAM_LIST")

            AppLog.d(TAG, "Plugin: $pluginName, params: ${paramList?.keySet()}")

            when (pluginName) {
                "BARCODE" -> {
                    scannerEnabled = paramList?.getString("scanner_input_enabled")?.toBoolean()
                }
                "INTENT" -> {
                    intentEnabled = paramList?.getString("intent_output_enabled")?.toBoolean()
                    intentAction = paramList?.getString("intent_action")
                    intentDelivery = when (paramList?.getString("intent_delivery")) {
                        "0" -> "Start Activity"
                        "1" -> "Start Service"
                        "2" -> "Broadcast intent"
                        else -> paramList?.getString("intent_delivery")
                    }
                }
                "KEYSTROKE" -> {
                    keystrokeEnabled = paramList?.getString("keystroke_output_enabled")?.toBoolean()
                }
            }
        }

        _status.update {
            it.copy(
                isQuerying = false,
                profileEnabled = profileEnabled?.toBoolean(),
                scannerEnabled = scannerEnabled,
                intentOutputEnabled = intentEnabled,
                intentAction = intentAction,
                intentDelivery = intentDelivery,
                keystrokeOutputEnabled = keystrokeEnabled
            )
        }

        unregisterReceiver()
    }
}
