package ua.com.programmer.pick.presentation.document

import androidx.annotation.StringRes
import ua.com.programmer.pick.R

sealed class DocumentDetailUiEvent {
    data class ShowToast(val messageType: ToastMessage) : DocumentDetailUiEvent()
}

enum class ToastMessage(@StringRes val resId: Int) {
    PRODUCT_NOT_IN_DOCUMENT(R.string.product_not_in_document),
    PRODUCT_NOT_FOUND(R.string.product_not_found),
    ERROR_SAVING(R.string.error_saving)
}
