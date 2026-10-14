package app.ezpztac.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the phone's frame does to what it hides (a tap on the map): on its way it says nothing and takes no touch, the map beside it keeping its own; once away it
 * is not laid out, and a touch where it was is the map's. And a sheet on its way takes the touches on what shows of it, never the map's above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class KeptAwayTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private var hidden by mutableStateOf(false)
    private var away by mutableStateOf(false)

    /** The map, and over it at 100, 100 a 200 dp square holding a button that fills it. */
    private fun show() = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            Box(Modifier.fillMaxSize().background(Color.DarkGray).clickable { log += "map" }) {
                Box(Modifier.offset(100.dp, 100.dp).size(200.dp).keptAway(hidden, away)) {
                    Button(onClick = { log += "button" }, modifier = Modifier.fillMaxSize()) { Text("Inside") }
                }
            }
        }
    }

    private fun tap(x: Float, y: Float) = compose.onRoot().performTouchInput { click(Offset(x, y)) }

    @Test
    fun `shown, it is read and touched`() {
        show()
        compose.onNodeWithText("Inside").assertExists()
        tap(200f, 200f)
        assertEquals(listOf("button"), log)
    }

    @Test
    fun `on its way it says nothing and takes no touch, and the map beside it keeps its own`() {
        show()
        hidden = true
        compose.waitForIdle()
        compose.onNodeWithText("Inside").assertDoesNotExist()
        tap(200f, 200f)                                                                              // on it: neither the button nor the map
        tap(380f, 700f)                                                                              // beside it: the map
        assertEquals(listOf("map"), log)
    }

    @Test
    fun `away, it is not laid out, so a touch where it was is the map's, and it comes back as it was`() {
        show()
        hidden = true
        away = true
        compose.waitForIdle()
        tap(200f, 200f)
        assertEquals(listOf("map"), log)
        hidden = false
        away = false
        compose.waitForIdle()
        tap(200f, 200f)
        assertEquals(listOf("map", "button"), log)
    }

    @Test
    fun `a sheet on its way takes the touches on what shows of it, and the map above it keeps its own`() {
        var inert by mutableStateOf(false)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).clickable { log += "map" }) {
                    PanelSheet(PanelSheetState(SheetDetent.Half), topLimit = 0.dp, inert = inert, header = { Text("Header") }) {
                        Button(onClick = { log += "button" }) { Text("In the sheet") }
                    }
                }
            }
        }
        val button = compose.onNodeWithText("In the sheet").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(button) }
        assertEquals(listOf("button"), log)
        inert = true
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(button) }                                       // the sheet takes it, and answers nothing
        compose.onRoot().performTouchInput { click(Offset(200f, 60f)) }                             // above the sheet: the map's
        assertEquals(listOf("button", "map"), log)
    }
}
