package app.ezpztac.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every Material Symbol the screens draw, loaded and drawn: a drawable that does not parse fails here, not on a screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h900dp-xxhdpi")
class IconGalleryTest {
    @get:Rule
    val compose = createComposeRule()

    private val icons: List<Pair<String, Int>> = EzpzIcons::class.java.declaredFields
        .filter { it.type == Int::class.javaPrimitiveType && !it.name.startsWith("$") }            // not the Compose compiler's `$stable`
        .map { field -> field.isAccessible = true; field.name to field.getInt(EzpzIcons) }
        .sortedBy { it.first }

    @Test
    fun `every icon in the list is in the object`() {
        // symbols.txt has 94 outlined and 19 filled; a drift between the list, the drawables and the object shows here first.
        assertEquals(113, icons.size)
        assertEquals(icons.size, icons.map { it.second }.toSet().size)
    }

    @OptIn(ExperimentalLayoutApi::class)
    private fun shot(name: String, mode: ThemeMode) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    FlowRow(Modifier.width(400.dp).padding(Tokens.Spacing.lg.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        icons.forEach { (iconName, id) ->
                            Icon(painterResource(id), contentDescription = iconName, tint = EzpzTheme.status.accent, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/icons-$name.png")
    }

    @Test fun dark() = shot("dark", ThemeMode.Dark)
    @Test fun night() = shot("night", ThemeMode.Night)
}
