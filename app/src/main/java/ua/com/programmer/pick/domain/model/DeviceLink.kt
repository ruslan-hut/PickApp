package ua.com.programmer.pick.domain.model

import java.net.URI
import java.net.URLDecoder

/**
 * Linking a device to a tenant without the global admin (server
 * `docs/device-api.md` § "Device linking"). Two ways in, same outcome: the
 * device scans the tenant's enrollment QR, or shows a pairing code the tenant
 * admin types into the web UI.
 */
enum class DeviceLinkStatus { PENDING, APPROVED, REJECTED;

    companion object {
        fun fromWire(value: String?): DeviceLinkStatus =
            entries.firstOrNull { it.name == value } ?: PENDING
    }
}

/**
 * Login refused because this device is not linked to a tenant — never linked
 * (DEVICE_PENDING), released or rejected (DEVICE_NOT_APPROVED), or approved
 * without a tenant (DEVICE_NO_TENANT). The cure is the pairing screen.
 */
class DeviceNotLinkedException(val code: String) : Exception(code) {
    companion object {
        val CODES = setOf("DEVICE_PENDING", "DEVICE_NOT_APPROVED", "DEVICE_NO_TENANT")
    }
}

/** One answer of the pairing poll. [code] and [expiresInSec] are set while PENDING. */
data class PairingState(
    val status: DeviceLinkStatus,
    val code: String? = null,
    val expiresInSec: Int? = null,
    val tenantName: String? = null,
)

/** A successful QR enrollment. */
data class LinkedTenant(val tenantName: String, val deviceName: String?)

sealed interface DeviceLinkResult<out T> {
    data class Success<T>(val value: T) : DeviceLinkResult<T>
    /** The server answered with a verdict ([code] from the error envelope). */
    data class Refused(val code: String?, val message: String?) : DeviceLinkResult<Nothing>
    /** No verdict: network down, timeout, unreadable response. */
    data class Unreachable(val message: String?) : DeviceLinkResult<Nothing>
}

/**
 * The tenant enrollment QR: `pick://enroll?s=<server origin>&t=<token>`.
 *
 * The app talks to one compiled-in server, so `s` is not used to switch
 * servers — it is checked, and a code issued by another server is refused
 * instead of being posted here (where it would only ever read as invalid).
 */
sealed interface EnrollmentQr {
    data class Valid(val token: String) : EnrollmentQr
    data class OtherServer(val server: String) : EnrollmentQr

    companion object {
        const val PREFIX = "pick://enroll?"

        /** Null when [raw] is not an enrollment QR at all (any other barcode). */
        fun parse(raw: String, expectedServer: String): EnrollmentQr? {
            val text = raw.trim()
            if (!text.startsWith(PREFIX)) return null
            val params = text.removePrefix(PREFIX).split('&').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null else pair.substring(0, i) to decode(pair.substring(i + 1))
            }.toMap()
            val token = params["t"]?.takeIf { it.isNotBlank() } ?: return null
            val server = params["s"]
            if (!server.isNullOrBlank() && origin(server) != origin(expectedServer)) {
                return OtherServer(server)
            }
            return Valid(token)
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            value
        }

        /** scheme://host[:port], lower-cased; null when unparsable. */
        private fun origin(url: String): String? = try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        } catch (e: Exception) {
            null
        }
    }
}
