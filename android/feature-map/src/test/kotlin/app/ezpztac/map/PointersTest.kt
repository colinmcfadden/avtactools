package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Everyone else's pointers in a Mission Pack (the design's PackLive): their colours, where they fall on the screen, and how one looks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h300dp-xxhdpi")
class PointersTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = LatLon(34.78, -84.08)

    @Test
    fun `a person is the colour the web gives them, by their id else their name`() {
        // The web's colorFor (Avatar.jsx), worked out for each key from its own rule.
        val expected = mapOf(
            "7" to 0xFFA89AE6, "1" to 0xFFE8A25C, "2" to 0xFF6FD0A8, "42" to 0xFFC7D36F, "1234" to 0xFF6FD0A8, "Sam Bell" to 0xFF9DB8E8,
            "Jess Reed" to 0xFFA89AE6, "é" to 0xFFE8A25C, "a".repeat(40) to 0xFF5AA7D4,
            // A character past the first plane counts once, by its first half, as the web's `for (const ch of key) ch.charCodeAt(0)` does.
            String(Character.toChars(0x1F681)) + "x" to 0xFFD98BC3,
        )
        expected.forEach { (key, color) -> assertEquals(key, color, PersonColors.of(key)) }
    }

    @Test
    fun `a pointer whose name still shows is drawn, one past the right or bottom edge, or far off, is not`() {
        // At zoom 16 a pixel is about a metre here; the view is 1000 by 2000 px with its middle at 500, 1000, and a density of 1.
        val view = MapProjection(CameraState(here, 16.0), 1000.0, 2000.0)
        fun pointer(key: String, east: Double, north: Double = 0.0, label: String = "Sam B.") = MapPointer(key, GraphicEdits.offset(here, north, east), label, 0xFFE8A25C)
        val placed = Pointers.placed(
            listOf(
                pointer("on", 400.0), pointer("tip just left, name showing", -540.0), pointer("far left", -800.0), pointer("tip past the right", 520.0),
                pointer("tip just above, name showing", 0.0, 1010.0), pointer("tip past the bottom", 0.0, -1020.0),
            ),
            view,
        )
        assertEquals(listOf("on", "tip just left, name showing", "tip just above, name showing"), placed.map { it.first.key })
        assertEquals(500.0, Pointers.placed(listOf(pointer("middle", 0.0)), view).single().second.x, 0.5)   // the tip is the place itself
        // A longer name, and larger text, reach further.
        assertEquals(0, Pointers.placed(listOf(pointer("short", -550.0, label = "Jo")), view).size)
        assertEquals(1, Pointers.placed(listOf(pointer("large text", -550.0, label = "Jo")), view, fontScale = 2.0).size)
    }

    @Test
    fun `in a right-to-left language a pointer stays on its place, its name to the right of the arrow`() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                EzpzTheme(ThemeMode.Dark) {
                    Box(Modifier.size(411.dp, 300.dp)) { PointerMarks(listOf(MapPointer("s-2", here, "Sam B.", 0xFFE8A25C) to ScreenPoint(150.0, 120.0))) }
                }
            }
        }
        val mark = compose.onNodeWithContentDescription("Sam B.'s pointer").fetchSemanticsNode().boundsInRoot
        assertEquals(150f, mark.left, 1f)
        assertEquals(120f, mark.top, 1f)
    }

    @Test
    fun `a pointer is an arrow in the person's colour with their name beside it, said to a screen reader`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.size(411.dp, 300.dp).background(Color(0xFF55703F))) {
                    PointerMark(MapPointer("s-2", here, "Sam B.", 0xFFE8A25C), ScreenPoint(300.0, 200.0))
                    PointerMark(MapPointer("s-3", here, "Jess R.", PersonColors.of("Jess Reed")), ScreenPoint(700.0, 420.0))
                }
            }
        }
        compose.onNodeWithContentDescription("Sam B.'s pointer").assertExists()
        compose.onNodeWithText("Sam B.").assertDoesNotExist()                                         // read once, as the design's words, not the name again
        compose.onRoot().captureRoboImage("build/screenshots/pointers.png")
    }
}
