package app.ezpztac.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun Tokens.Type.Style.toTextStyle() = TextStyle(
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
)

/** Key numbers and grids stay readable at arm's length, and use a monospaced face so digits do not shift. */
object EzpzText {
    val keyNumber: TextStyle = Tokens.Type.keyNumber.toTextStyle()
    val grid: TextStyle = Tokens.Type.grid.toTextStyle()
}

internal fun ezpzTypography(): Typography = Typography(
    headlineMedium = Tokens.Type.headline.toTextStyle(),
    titleLarge = Tokens.Type.title.toTextStyle(),
    bodyLarge = Tokens.Type.body.toTextStyle(),
    labelLarge = Tokens.Type.label.toTextStyle(),
    bodySmall = Tokens.Type.caption.toTextStyle(),
)
