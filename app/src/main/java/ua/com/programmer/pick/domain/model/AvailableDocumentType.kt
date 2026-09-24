package ua.com.programmer.pick.domain.model

/**
 * Per-document-type capability flags sent by the server with [code]/[description].
 *
 * Each flag is nullable: `null` means the ERP did not specify the option and
 * the client applies its own default via the `*OrDefault` accessors. This keeps
 * the contract backward-compatible with ERP payloads that predate these flags.
 *
 *  - [allowsOverPlan] (default `false`): permit `actualQuantity > plannedQuantity`
 *    on scans, `+` presses, and manual edits. Typical for `INCOMING_RECEIPT`
 *    where over-delivery is legitimate.
 *  - [allowsExtraLines] (default `false`): scanning an unknown product creates
 *    a new line on the fly. Today implicit for `INVENTORY`.
 *  - [mode]: `"guided"` marks a WMS guided task type rather than a document
 *    type — tapping it starts a server-driven task instead of opening a list.
 *    Null on a tenant/warehouse without the addressing module.
 *  - [requiresPlan] (default `true`): lines are expected to carry a plan; UI
 *    displays plan labels and the progress bar uses the plan value.
 *  - [wmsFlow]: `"collect"` / `"receive"` — the guided WMS flow documents of
 *    this classic type run. Sent only when the worker's warehouse has the
 *    module on, so its presence is also the app's "WMS is on here" signal.
 */
data class AvailableDocumentType(
    val code: String,
    val description: String,
    val allowsOverPlan: Boolean? = null,
    val allowsExtraLines: Boolean? = null,
    val requiresPlan: Boolean? = null,
    val mode: String? = null,
    val wmsFlow: String? = null
) {
    val isGuided: Boolean get() = mode == GUIDED_MODE

    /** Documents of this type are received as a guided WMS task. */
    val isGuidedReceiving: Boolean get() = wmsFlow == WMS_FLOW_RECEIVE

    fun allowsOverPlanOrDefault(): Boolean = allowsOverPlan ?: false
    fun allowsExtraLinesOrDefault(): Boolean = allowsExtraLines ?: false
    fun requiresPlanOrDefault(): Boolean = requiresPlan ?: true

    companion object {
        const val GUIDED_MODE = "guided"
        const val WMS_FLOW_RECEIVE = "receive"
    }
}
