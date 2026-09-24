package ua.com.programmer.pick.domain.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The part of a line's actual quantity the worker collected by scanning a
 * batch label (server: `DocumentLine.batches`). A product scan adds to the
 * quantity only; the ERP assigns batches for that part itself (FEFO).
 *
 * [batchId] is the batch's ERP external_id, as the server's product lookup
 * returns it (`batch.id`) for a scanned batch label.
 */
data class LineBatch(
    val batchId: String,
    val qty: Double,
)

/**
 * The batch behind a scanned batch label, as the server's product lookup
 * returns it. [id] is what [LineBatch.batchId] carries.
 */
data class ScannedBatch(
    val id: String,
    val number: String? = null,
    val expiryDate: Long? = null,
)

/**
 * A barcode resolved by the server: the local product row it maps to and, for
 * a batch label, the batch the scan is credited to.
 */
data class ProductLookupHit(
    val productId: String,
    val batch: ScannedBatch? = null,
)

/**
 * Mirrors the server's `entity.NormalizeLineBatches`: drop blank ids and
 * non-positive quantities, merge repeats (first position kept), and when the
 * sum exceeds [actual] take the excess off the last entries — a quantity
 * walked back undoes the latest scans first.
 */
fun List<LineBatch>.normalizedTo(actual: Double): List<LineBatch> {
    val merged = LinkedHashMap<String, Double>()
    for (b in this) {
        if (b.batchId.isBlank() || b.qty <= 0.0) continue
        merged[b.batchId] = (merged[b.batchId] ?: 0.0) + b.qty
    }
    val out = merged.map { (id, q) -> LineBatch(id, q) }.toMutableList()
    var excess = out.sumOf { it.qty } - actual
    while (excess > 0.0 && out.isNotEmpty()) {
        val last = out.last()
        if (last.qty <= excess) {
            excess -= last.qty
            out.removeAt(out.lastIndex)
        } else {
            out[out.lastIndex] = last.copy(qty = last.qty - excess)
            excess = 0.0
        }
    }
    return out
}

/** Adds [qty] of [batchId] to the breakdown (a batch-label scan). */
fun List<LineBatch>.credit(batchId: String, qty: Double): List<LineBatch> {
    val i = indexOfFirst { it.batchId == batchId }
    if (i < 0) return this + LineBatch(batchId, qty)
    return toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + qty) }
}

/**
 * Room stores the breakdown as JSON text. null = the device holds no opinion
 * (never edited here, nothing from the server) — the PATCH then omits the
 * field and the server keeps what it has; "[]" = explicitly empty.
 */
object LineBatchJson {
    private val gson = Gson()
    private val type = object : TypeToken<List<LineBatch>>() {}.type

    fun encode(batches: List<LineBatch>?): String? = batches?.let { gson.toJson(it) }

    fun decode(json: String?): List<LineBatch>? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson<List<LineBatch>>(json, type) }.getOrNull()
    }
}
