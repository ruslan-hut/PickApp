package ua.com.programmer.pick.core.scanner

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences wrapper for hardware barcode scanner detection settings.
 * Allows users to tune detection for their specific scanner model,
 * connection type (USB/Bluetooth), and Android device.
 */
@Singleton
class ScannerSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Max gap (ms) between keystrokes to treat them as one scan burst. */
    var keystrokeTimeout: Long
        get() = prefs.getLong(KEY_TIMEOUT, DEFAULT_TIMEOUT)
        set(value) = prefs.edit().putLong(KEY_TIMEOUT, value.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)).apply()

    /** Minimum characters required before a terminator triggers barcode dispatch. */
    var minBarcodeLength: Int
        get() = prefs.getInt(KEY_MIN_LENGTH, DEFAULT_MIN_LENGTH)
        set(value) = prefs.edit().putInt(KEY_MIN_LENGTH, value.coerceIn(MIN_LENGTH, MAX_LENGTH)).apply()

    /** Which key code acts as scan terminator: "enter", "tab", or "both". */
    var terminatorKey: String
        get() = prefs.getString(KEY_TERMINATOR, DEFAULT_TERMINATOR) ?: DEFAULT_TERMINATOR
        set(value) = prefs.edit().putString(KEY_TERMINATOR, value).apply()

    /** Optional prefix the scanner prepends — will be stripped from the barcode string. */
    var prefixToStrip: String
        get() = prefs.getString(KEY_PREFIX, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PREFIX, value).apply()

    /** Optional suffix the scanner appends before the terminator — will be stripped. */
    var suffixToStrip: String
        get() = prefs.getString(KEY_SUFFIX, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUFFIX, value).apply()

    /**
     * Applies prefix/suffix stripping to a raw barcode string.
     */
    fun cleanBarcode(raw: String): String {
        var result = raw
        val prefix = prefixToStrip
        val suffix = suffixToStrip
        if (prefix.isNotEmpty() && result.startsWith(prefix)) {
            result = result.removePrefix(prefix)
        }
        if (suffix.isNotEmpty() && result.endsWith(suffix)) {
            result = result.removeSuffix(suffix)
        }
        return result
    }

    fun isTerminator(keyCode: Int): Boolean {
        return when (terminatorKey) {
            TERMINATOR_ENTER -> keyCode == KeyEvent.KEYCODE_ENTER
            TERMINATOR_TAB -> keyCode == KeyEvent.KEYCODE_TAB
            else -> keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB
        }
    }

    companion object {
        private const val PREFS_NAME = "scanner_settings"

        private const val KEY_TIMEOUT = "keystroke_timeout"
        private const val KEY_MIN_LENGTH = "min_barcode_length"
        private const val KEY_TERMINATOR = "terminator_key"
        private const val KEY_PREFIX = "prefix_to_strip"
        private const val KEY_SUFFIX = "suffix_to_strip"

        const val DEFAULT_TIMEOUT = 200L
        const val MIN_TIMEOUT = 50L
        const val MAX_TIMEOUT = 500L

        const val DEFAULT_MIN_LENGTH = 4
        const val MIN_LENGTH = 1
        const val MAX_LENGTH = 20

        const val TERMINATOR_ENTER = "enter"
        const val TERMINATOR_TAB = "tab"
        const val TERMINATOR_BOTH = "both"
        const val DEFAULT_TERMINATOR = TERMINATOR_BOTH
    }
}
