package ua.com.programmer.pick.presentation.task

import androidx.annotation.StringRes
import ua.com.programmer.pick.R

sealed class TaskUiEvent {
    /**
     * Chrome-level notice. Server-authored text always wins when present —
     * the step's own texts are already in the tenant's locale (D8).
     */
    data class ShowToast(val messageType: TaskToastMessage, val serverText: String? = null) : TaskUiEvent()
    data object NavigateHome : TaskUiEvent()
    data class NavigateToDocuments(val documentId: String?) : TaskUiEvent()
    /** Haptic feedback on a refusal, so a gloved worker notices without looking. */
    data object VibrateError : TaskUiEvent()
}

/** App strings around the step; never a substitute for a server message. */
enum class TaskToastMessage(@StringRes val resId: Int) {
    FEATURE_DISABLED(R.string.task_error_feature_disabled),
    GUIDED_OFF(R.string.task_error_guided_off),
    NO_WAREHOUSE(R.string.task_error_no_warehouse),
    NOT_FOUND(R.string.task_error_not_found),
    FORBIDDEN(R.string.task_error_forbidden),
    WRONG_STATE(R.string.task_error_wrong_state),
    DEMO(R.string.task_error_demo),
    OFFLINE(R.string.task_offline_hint),
    GENERIC(R.string.task_error_generic)
}
