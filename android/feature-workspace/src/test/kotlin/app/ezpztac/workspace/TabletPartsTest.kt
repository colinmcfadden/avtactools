package app.ezpztac.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the tablet asks of the panels (S9): the LZ/PZ panel drawn in two panes, and the Library and the import review as large dialogs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class TabletPartsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `on a tablet the plan page is the pane beside the map, and the list shows the open LZ-PZs under it`() {
        assertEquals(LzPage.PLAN, LzPane.WHOLE.shows(LzPage.PLAN))
        assertEquals(LzPage.HOME, LzPane.LIST.shows(LzPage.PLAN))
        assertEquals(LzPage.AIRCRAFT, LzPane.LIST.shows(LzPage.AIRCRAFT))
        assertEquals(LzPage.HOME, LzPane.LIST.shows(LzPage.HOME))
        assertEquals(LzPage.PLAN, LzPane.PLAN.shows(LzPage.PLAN))
        assertEquals(null, LzPane.PLAN.shows(LzPage.HOME))
        assertEquals(null, LzPane.PLAN.shows(LzPage.AIRCRAFT))
    }

    private fun dialog(onDismiss: (() -> Unit)?) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            Box(Modifier.fillMaxSize().background(Color(0xFF3A4A3A))) {                            // the frame's stand-in
                LargeDialog(onDismiss = onDismiss) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101317)).padding(24.dp)) { Text("What the dialog holds", color = Color.White) }
                }
            }
        }
    }

    @Test
    fun `a large dialog is no wider than the design's 880 dp, and a touch beside it closes it while one on it does not`() {
        var closed = 0
        dialog { closed++ }
        val width = with(compose.density) { compose.onNodeWithTag(LARGE_DIALOG_TAG).fetchSemanticsNode().size.width.toDp() }
        assertEquals(880f, width.value, 0.5f)
        compose.onNodeWithText("What the dialog holds").performClick()
        assertEquals(0, closed)
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(20f, 20f)) }   // on the scrim, beside the dialog
        assertEquals(1, closed)
        // The scrim is no node of its own: a screen reader and the keyboard close the dialog with its own close button and Back.
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `a large dialog in a window of its own has no scrim to touch`() {
        dialog(null)
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(20f, 20f)) }   // nothing to close: the platform dialog's own window dims
        compose.onNodeWithText("What the dialog holds").assertExists()
    }

    @Test
    fun `the Library as a tablet's large dialog`() {
        val state = LibraryUiState(
            rows = listOf(
                LibraryRowUi("lz-1", LibraryTab.LZ, "LZ HAWK (ROCK)", grid = "16S GC 31204 58811", updated = "Updated Oct 3 · 09:12", inSession = true),
                LibraryRowUi("lz-2", LibraryTab.LZ, "LZ HAWK (SHOPE)", grid = "16S GC 24118 57290", updated = "Updated Aug 25 · 17:34"),
                LibraryRowUi("lz-3", LibraryTab.LZ, "LZ RAZORBILL", grid = "16S GC 22906 55120", updated = "Updated Aug 25 · 17:27"),
            ),
            counts = mapOf(LibraryTab.LZ to 12, LibraryTab.ROUTES to 4, LibraryTab.POINTS to 3),
        )
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(Color(0xFF3A4A3A))) {
                    LargeDialog(onDismiss = {}) { LibraryPage(state, LibraryActions()) }
                }
            }
        }
        val width = with(compose.density) { compose.onNodeWithText("Library").fetchSemanticsNode().boundsInRoot.left.toDp() }
        assertTrue("the Library's title is inside the dialog, not at the window's edge", width.value > 150f)
        compose.onRoot().captureRoboImage("build/screenshots/tablet-library.png")
    }
}
