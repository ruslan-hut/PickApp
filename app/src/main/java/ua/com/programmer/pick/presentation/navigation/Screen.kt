package ua.com.programmer.pick.presentation.navigation

sealed class Screen(val route: String) {

    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object Home : Screen("home")

    // Future screens
    data object Documents : Screen("documents")
    data object DocumentDetail : Screen("document/{documentId}") {
        fun createRoute(documentId: String) = "document/$documentId"
    }
    data object CollectorQueue : Screen("collector_queue")
    data object BoxScanning : Screen("box_scanning/{documentId}") {
        fun createRoute(documentId: String) = "box_scanning/$documentId"
    }
    data object Courier : Screen("courier")
    data object Settings : Screen("settings")
    data object ScannerSettings : Screen("scanner_settings")
    data object ScannerTest : Screen("scanner_test")
    data object Profile : Screen("profile")

    companion object {
        const val DOCUMENT_ID_ARG = "documentId"
    }
}
