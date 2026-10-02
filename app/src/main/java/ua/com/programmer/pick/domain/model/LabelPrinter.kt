package ua.com.programmer.pick.domain.model

/**
 * A label printer of the warehouse, set up in the tenant UI. The terminal only
 * needs where to send jobs: the label profile stays on the server, which
 * renders every job for it.
 */
data class LabelPrinter(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val labelWidthMm: Double,
    val labelHeightMm: Double,
) {
    val address: String get() = "$host:$port"
}
