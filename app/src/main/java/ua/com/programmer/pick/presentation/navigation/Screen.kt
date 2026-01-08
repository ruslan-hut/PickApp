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
    data object Settings : Screen("settings")
    data object Profile : Screen("profile")

    companion object {
        const val DOCUMENT_ID_ARG = "documentId"
    }
}
