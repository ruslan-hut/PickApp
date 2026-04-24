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
 *  - [requiresPlan] (default `true`): lines are expected to carry a plan; UI
 *    displays plan labels and the progress bar uses the plan value.
 */
data class AvailableDocumentType(
    val code: String,
    val description: String,
    val allowsOverPlan: Boolean? = null,
    val allowsExtraLines: Boolean? = null,
    val requiresPlan: Boolean? = null
) {
    fun allowsOverPlanOrDefault(): Boolean = allowsOverPlan ?: false
    fun allowsExtraLinesOrDefault(): Boolean = allowsExtraLines ?: false
    fun requiresPlanOrDefault(): Boolean = requiresPlan ?: true
}
