package sealed

import androidx.annotation.DrawableRes
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.navigation.Screen

open class BottomNavItem(
    val route: String,
    val titleResId: Int,
    @DrawableRes val selectedIcon: Int,
    @DrawableRes val unselectedIcon: Int
) {
    data object Home : BottomNavItem(
        route = Screen.Home.route,
        titleResId = R.string.nav_home,
        selectedIcon = R.drawable.baseline_home_filled_24,
        unselectedIcon = R.drawable.outline_home_24
    )

    data object Documents : BottomNavItem(
        route = Screen.Documents.route,
        titleResId = R.string.nav_documents,
        selectedIcon = R.drawable.baseline_description_24,
        unselectedIcon = R.drawable.outline_description_24
    )

    data object Profile : BottomNavItem(
        route = Screen.Profile.route,
        titleResId = R.string.nav_profile,
        selectedIcon = R.drawable.baseline_person_24,
        unselectedIcon = R.drawable.outline_person_24
    )

    companion object {
        val items = listOf(Home, Documents, Profile)
    }
}