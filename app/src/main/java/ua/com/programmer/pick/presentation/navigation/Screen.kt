package ua.com.programmer.pick.presentation.navigation

import android.net.Uri

sealed class Screen(val route: String) {

    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object Home : Screen("home")

    // Future screens
    data object Documents : Screen("documents")
    data object DocumentDetail : Screen("document/{documentId}") {
        fun createRoute(documentId: String) = "document/$documentId"
    }
    data object Courier : Screen("courier")
    data object Settings : Screen("settings")
    data object ScannerSettings : Screen("scanner_settings")
    data object ScannerTest : Screen("scanner_test")
    data object Profile : Screen("profile")
    data object ScanLogin : Screen("scan_login")
    data object DebugJournal : Screen("debug_journal")

    /**
     * "Connect to company". [ENROLL_QR_ARG] optionally carries an enrollment QR
     * that was scanned on the login screen, so the link starts right away.
     */
    data object DevicePairing : Screen("device_pairing?qr={qr}") {
        fun create(enrollQr: String? = null) = "device_pairing?qr=${Uri.encode(enrollQr.orEmpty())}"
    }

    /**
     * The generic guided-task screen. Exactly one argument is meaningful per
     * navigation: [TASK_ID_ARG] resumes an open task, [TASK_TYPE_ARG] starts a
     * system task type, [DOCUMENT_ID_ARG] starts (or resumes) the task bound to
     * a document.
     */
    data object Task : Screen("task?taskId={taskId}&type={type}&documentId={documentId}") {
        fun byTaskId(taskId: String) = "task?taskId=$taskId&type=&documentId="
        fun byType(type: String) = "task?taskId=&type=$type&documentId="
        fun byDocument(documentId: String) = "task?taskId=&type=&documentId=$documentId"
    }

    companion object {
        const val DOCUMENT_ID_ARG = "documentId"
        const val TASK_ID_ARG = "taskId"
        const val TASK_TYPE_ARG = "type"
        const val ENROLL_QR_ARG = "qr"
    }
}
