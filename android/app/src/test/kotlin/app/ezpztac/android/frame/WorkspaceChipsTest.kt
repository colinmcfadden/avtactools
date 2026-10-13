package app.ezpztac.android.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.map.ChipAction
import app.ezpztac.map.ChipsThatFit
import app.ezpztac.map.MapChip
import app.ezpztac.map.MapChrome
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.packs.OpenPackUi
import app.ezpztac.packs.PackChip
import app.ezpztac.packs.PackStatusLine
import app.ezpztac.packs.PackWords
import app.ezpztac.packs.PersonUi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chips under the search bar in a pack, on a 360 dp phone at large text (the review's case): Import and the Library keep their place and give up their
 * words, and the pack's name is cut, before anything leaves the screen. `-Pezpz.screenshots` draws them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class WorkspaceChipsTest {
    @get:Rule
    val compose = createComposeRule()

    private val people = listOf(PersonUi(7, "Colin McFadden", "CM", me = true), PersonUi(9, "Sam B.", "SB", false), PersonUi(11, "Jess R.", "JR", false), PersonUi(12, "Kim L.", "KL", false))
    private val long = OpenPackUi("p-ex", "B CO FIELD EXERCISE NORTH GEORGIA", people, PackStatus.OFFLINE, finished = false, readOnly = false, pending = 2)

    private fun show(pack: OpenPackUi?) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF3A4A3A)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChipsThatFit(
                    lead = {
                        if (pack != null) PackChip(pack, onClick = {}) else MapChip(EzpzIcons.person, "Personal", description = "Workspace: Personal")
                    },
                    chips = listOf(
                        ChipAction(EzpzIcons.fileOpen, "Import", "Import a mission, local points or threats") {},
                        ChipAction(EzpzIcons.collectionsBookmark, "Library", "Library: everything you have saved") {},
                    ),
                    modifier = Modifier.height(MapChrome.CHIPS_HEIGHT),
                )
                if (pack != null) Column(Modifier.padding(top = 8.dp, end = MapChrome.BUTTON + 8.dp)) { PackStatusLine(pack) }
            }
        }
    }

    private fun onScreen(description: String) {
        val node = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$description is cut off: ${node.boundsInRoot} in $root", node.boundsInRoot.right <= root.right - 16 && node.boundsInRoot.width >= 40)
    }

    @Test
    @Config(fontScale = 1.3f)
    fun `a long name and four people leave Import and the Library on the screen, as icons`() {
        show(long)
        onScreen("Import a mission, local points or threats")
        onScreen("Library: everything you have saved")
        onScreen(PackWords.chipDescription(long))
        compose.onNodeWithText("Library", useUnmergedTree = true).assertDoesNotExist()            // its words gave way, and are nowhere a screen reader looks
        compose.onNodeWithText("Import", useUnmergedTree = true).assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/screenshots/workspace-chips-narrow.png")
    }

    @Test
    fun `the Library keeps its words where they fit`() {
        show(null)
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("Import").assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at the largest text nothing leaves the screen`() {
        show(long)
        onScreen("Library: everything you have saved")
        onScreen(PackWords.chipDescription(long))
        compose.onNodeWithText(PackWords.status(long)).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/workspace-chips-largest.png")
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at the largest text a finished pack keeps its name, a lock standing for its words`() {
        val finished = OpenPackUi("p-dk", "OP DK", people, PackStatus.LIVE, finished = true, readOnly = true, pending = 0, role = "owner")
        show(finished)
        onScreen(PackWords.chipDescription(finished))
        compose.onNodeWithText("· Finished", useUnmergedTree = true).assertDoesNotExist()
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("OP DK", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertFalse("the name is cut", layout.single().isLineEllipsized(0))
        compose.onRoot().captureRoboImage("build/screenshots/workspace-chips-finished-largest.png")
    }
}
