package ua.com.programmer.pick.presentation.document

import androidx.annotation.StringRes
import ua.com.programmer.pick.R

sealed class DocumentDetailUiEvent {
    data class ShowToast(val messageType: ToastMessage) : DocumentDetailUiEvent()
    data class ShowBarcodeAlert(val alertType: BarcodeAlertType) : DocumentDetailUiEvent()
    data object NavigateBack : DocumentDetailUiEvent()
}

enum class ToastMessage(@StringRes val resId: Int) {
    PRODUCT_NOT_FOUND(R.string.product_not_found),
    ERROR_SAVING(R.string.error_saving),
    DOCUMENT_TAKEN_INTO_WORK(R.string.document_taken_into_work),
    DOCUMENT_PACKAGED(R.string.document_packaged),
    DOCUMENT_COMPLETED(R.string.document_completed),
    DOCUMENT_RELEASED(R.string.document_released),
    ERROR_TAKE_INTO_WORK(R.string.error_take_into_work),
    ERROR_PACKAGE_DOCUMENT(R.string.error_package_document),
    ERROR_COMPLETE_DOCUMENT(R.string.error_complete_document),
    ERROR_RELEASE_DOCUMENT(R.string.error_release_document),
    DOCUMENT_ALREADY_TAKEN(R.string.document_already_taken),
    DOCUMENT_TAKEN_BY_OTHER(R.string.document_taken_by_other),
    CANNOT_EDIT_DOCUMENT(R.string.cannot_edit_document)
}

enum class BarcodeAlertType(@StringRes val resId: Int) {
    /** Scanned barcode does not match any line in the document — red */
    PRODUCT_NOT_IN_DOCUMENT(R.string.product_not_in_document),
    /** Scanned product is already fully collected — yellow */
    PRODUCT_ALREADY_COMPLETED(R.string.product_already_completed)
}
