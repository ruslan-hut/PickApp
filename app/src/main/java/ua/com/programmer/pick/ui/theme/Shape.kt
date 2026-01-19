package ua.com.programmer.pick.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Custom shape definitions for PickApp
// Following Material 3 shape scale guidelines

val Shapes = Shapes(
    // Extra small - small chips, badges
    extraSmall = RoundedCornerShape(4.dp),

    // Small - input fields, small cards
    small = RoundedCornerShape(8.dp),

    // Medium - standard cards, dialogs
    medium = RoundedCornerShape(12.dp),

    // Large - large cards, bottom sheets
    large = RoundedCornerShape(16.dp),

    // Extra large - FABs, prominent elements
    extraLarge = RoundedCornerShape(28.dp)
)

// Additional custom shapes for specific use cases
val BottomSheetShape = RoundedCornerShape(
    topStart = 28.dp,
    topEnd = 28.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp
)

val TopSheetShape = RoundedCornerShape(
    topStart = 0.dp,
    topEnd = 0.dp,
    bottomStart = 28.dp,
    bottomEnd = 28.dp
)

val CardShape = RoundedCornerShape(12.dp)

val ButtonShape = RoundedCornerShape(12.dp)

val ChipShape = RoundedCornerShape(8.dp)

val SearchBarShape = RoundedCornerShape(28.dp)

val FabShape = RoundedCornerShape(16.dp)
