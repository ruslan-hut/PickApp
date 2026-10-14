package ua.com.programmer.pick.data.notice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ua.com.programmer.pick.MainActivity
import ua.com.programmer.pick.R
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.domain.model.NewDocumentNotice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System notifications of the new-document notice: the one that shows on a
 * locked or switched-off screen, and the permanent one a foreground service
 * has to carry. While the app is open the in-app banner does the job instead,
 * so [show] is only called once the app has left the foreground.
 */
@Singleton
class QueueNoticeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        const val SERVICE_NOTIFICATION_ID = 4101
        private const val TAG = "QueueNoticeNotifier"
        private const val NEW_DOCUMENT_NOTIFICATION_ID = 4102
        private const val CHANNEL_SERVICE = "queue_notice_service"
        private const val CHANNEL_NEW_DOCUMENT = "queue_notice_new_document"
        private const val MAX_LINES = 5
        private const val WAKE_MS = 5_000L
    }

    private val manager = NotificationManagerCompat.from(context)
    private val pending = LinkedHashMap<String, NewDocumentNotice>()

    /** Notice that has not been looked at yet; grows until the worker opens the app. */
    @Synchronized
    fun show(documents: List<NewDocumentNotice>) {
        documents.forEach { pending[it.id] = it }
        if (pending.isEmpty() || !canPost()) return
        ensureChannels()

        val lines = pending.values.map(::line)
        val title = if (lines.size == 1) {
            context.getString(R.string.new_document_title)
        } else {
            context.getString(R.string.new_documents_title, lines.size)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_NEW_DOCUMENT)
            .setSmallIcon(R.drawable.baseline_description_24)
            .setContentTitle(title)
            .setContentText(lines.last())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        if (lines.size > 1) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            lines.takeLast(MAX_LINES).forEach(style::addLine)
            if (lines.size > MAX_LINES) {
                style.setSummaryText(context.getString(R.string.new_documents_more, lines.size - MAX_LINES))
            }
            builder.setStyle(style)
        }
        runCatching { manager.notify(NEW_DOCUMENT_NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLog.w(TAG, "notify failed: ${it.message}") }
        wakeScreen()
    }

    /**
     * Lights a sleeping screen for a few seconds so the notice is seen without
     * touching the terminal. The flags are deprecated but remain the only way
     * to turn the screen on from a service without a full-screen intent, which
     * Play restricts to calls and alarms.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            val power = context.getSystemService(PowerManager::class.java) ?: return
            if (power.isInteractive) return
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "pick:queue-notice",
            ).acquire(WAKE_MS)
        }.onFailure { AppLog.w(TAG, "wake failed: ${it.message}") }
    }

    /** The app is in front again: the banner has shown what the notification held. */
    @Synchronized
    fun clear() {
        if (pending.isEmpty()) return
        pending.clear()
        manager.cancel(NEW_DOCUMENT_NOTIFICATION_ID)
    }

    /** The service's own, quiet notification. */
    fun serviceNotification(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.baseline_description_24)
            .setContentTitle(context.getString(R.string.queue_notice_service_title))
            .setContentText(context.getString(R.string.queue_notice_service_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun line(doc: NewDocumentNotice): String =
        doc.salesChannel
            ?.let { context.getString(R.string.new_document_line_channel, doc.number, it) }
            ?: context.getString(R.string.new_document_line, doc.number)

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_DOCUMENT,
                context.getString(R.string.queue_notice_channel_documents),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
            }
        )
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.queue_notice_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }
}
