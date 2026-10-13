package ua.com.programmer.pick.core.util

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The short sound + vibration that accompanies an in-app notice. */
@Singleton
class NoticeAlert @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private companion object {
        const val TAG = "NoticeAlert"
        const val VIBRATE_MS = 300L
    }

    fun play() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }.onFailure { AppLog.w(TAG, "sound failed: ${it.message}") }
        runCatching { vibrate() }.onFailure { AppLog.w(TAG, "vibration failed: ${it.message}") }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBRATE_MS)
        }
    }
}
