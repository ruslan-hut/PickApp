package ua.com.programmer.pick.core.util

import android.util.Log
import ua.com.programmer.pick.BuildConfig

/**
 * Logging wrapper. Logcat output is gated on DEBUG builds, but INFO/WARN/ERROR
 * are always mirrored to [FileLogger] so that users can export logs from the
 * Settings screen for troubleshooting.
 */
object AppLog {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
        FileLogger.log("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
        FileLogger.log("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.w(tag, msg)
        FileLogger.log("W", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, tr)
        FileLogger.log("W", tag, msg, tr)
    }

    fun e(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.e(tag, msg)
        FileLogger.log("E", tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.e(tag, msg, tr)
        FileLogger.log("E", tag, msg, tr)
    }

    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
        FileLogger.log("V", tag, msg)
    }
}
