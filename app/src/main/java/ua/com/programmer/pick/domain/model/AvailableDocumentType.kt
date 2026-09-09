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
 */
data class AvailableDocumentType(
    val code: String,
    val description: String,
    val allowsOverPlan: Boolean? = null,
    val allowsExtraLines: Boolean? = null,
    val requiresPlan: Boolean? = null,
    val mode: String? = null
) {
    val isGuided: Boolean get() = mode == GUIDED_MODE

    fun allowsOverPlanOrDefault(): Boolean = allowsOverPlan ?: false
    fun allowsExtraLinesOrDefault(): Boolean = allowsExtraLines ?: false
    fun requiresPlanOrDefault(): Boolean = requiresPlan ?: true

    companion object {
        const val GUIDED_MODE = "guided"

        /**
         * The one ERP type code the app knows by name. It gates two cosmetic
         * choices only — the guided bar's wording and the "join receiving"
         * shortcut — never what the server returns or allows. See the guided-
         * tasks plan §8: a `wms_enabled` flag on the login response would let
         * the app drop even this.
         */
        const val CODE_INCOMING_RECEIPT = "INCOMING_RECEIPT"
    }
}
