package ua.com.programmer.pick.core.util

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Fail-safe wrapper around Firebase Crashlytics. The Firebase Gradle plugins
 * are applied only when app/google-services.json is present, so FirebaseApp
 * may not be initialized at runtime — in that case every call here is a no-op.
 * Calls must never throw into business logic.
 */
object CrashReporter {
    @Volatile
    private var crashlytics: FirebaseCrashlytics? = null

    fun initialize(context: Context) {
        crashlytics = try {
            if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseCrashlytics.getInstance() else null
        } catch (e: Exception) {
            null
        }
    }

    val isAvailable: Boolean get() = crashlytics != null

    /** Identifies the device/worker in crash reports. */
    fun setUserId(id: String) {
        runCatching { crashlytics?.setUserId(id) }
    }

    fun setKey(key: String, value: String) {
        runCatching { crashlytics?.setCustomKey(key, value) }
    }

    /** In-memory breadcrumb attached to the next crash report. Cheap; safe on hot paths. */
    fun breadcrumb(message: String) {
        runCatching { crashlytics?.log(message) }
    }

    /** Non-fatal exception report. */
    fun record(throwable: Throwable) {
        runCatching { crashlytics?.recordException(throwable) }
    }
}
