package ua.com.programmer.pick.data.notice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ua.com.programmer.pick.core.util.AppLog
import javax.inject.Inject

/**
 * Keeps the process, and with it [NewDocumentNotifier]'s poll, alive while the
 * app is in the background, and turns what the poll finds into a system
 * notification. Runs only while the worker has the notice option on.
 */
@AndroidEntryPoint
class QueueNoticeService : Service() {

    @Inject lateinit var notifier: NewDocumentNotifier
    @Inject lateinit var notifications: QueueNoticeNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, QueueNoticeNotifier.SERVICE_NOTIFICATION_ID, notifications.serviceNotification(), type)

        // The in-app banner covers a visible app; only an app out of sight needs the notification.
        notifier.notices
            .filter { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            .onEach { notifications.show(it) }
            .launchIn(scope)

        notifier.active
            .filter { !it }
            .onEach {
                AppLog.i(TAG, "notice option off or signed out, stopping")
                stopSelf()
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QueueNoticeService"

        /** Must be called while the app is in the foreground; Android refuses it otherwise. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, QueueNoticeService::class.java))
            }.onFailure { AppLog.w(TAG, "could not start: ${it.message}") }
        }
    }
}
