package app.ezpztac.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun Tokens.Type.Style.toTextStyle() = TextStyle(
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = FontWeight(weight),
)

/** Key numbers and grids stay readable at arm's length, and use a monospaced face so digits do not shift. */
object EzpzText {
    val keyNumber: TextStyle = Tokens.Type.keyNumber.toTextStyle()
    val grid: TextStyle = Tokens.Type.grid.toTextStyle()
}

/** The redesign's type scale (docs/native-design/tokens.json): the roles it names; the rest keep Material 3's. */
internal fun ezpzTypography(): Typography = Typography(
    headlineSmall = Tokens.Type.headlineSmall.toTextStyle(),
    titleLarge = Tokens.Type.titleLarge.toTextStyle(),
    titleMedium = Tokens.Type.titleMedium.toTextStyle(),
    bodyLarge = Tokens.Type.bodyLarge.toTextStyle(),
    bodyMedium = Tokens.Type.bodyMedium.toTextStyle(),
    bodySmall = Tokens.Type.bodySmall.toTextStyle(),
    labelLarge = Tokens.Type.labelLarge.toTextStyle(),
    labelMedium = Tokens.Type.labelMedium.toTextStyle(),
)

/**
 * The redesign's corners: chips, text fields and toasts 8 dp (small); cards and menus 16 (large); sheets, dialogs and the search bar 28
 * (extra large). Buttons stay Material's full pill.
 */
internal fun ezpzShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(Tokens.Radius.sm.dp),
    medium = RoundedCornerShape(Tokens.Radius.md.dp),
    large = RoundedCornerShape(Tokens.Radius.lg.dp),
    extraLarge = RoundedCornerShape(Tokens.Radius.sheet.dp),
)
