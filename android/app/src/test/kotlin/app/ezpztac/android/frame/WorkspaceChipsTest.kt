package app.ezpztac.android.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.map.GpsState
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapStyles
import app.ezpztac.map.MapUiState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.packs.OpenPackUi
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
 * The chips on the phone's row in a pack, between the folded search and the account (the owner's decision of 2026-10-13), on a 360 dp phone at large text (the
 * review's case): Import and the Library keep their place and give up their words, then the pack's faces and its arrow give way, and only then is the pack's
 * name cut, before anything leaves the screen. `-Pezpz.screenshots` draws them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class WorkspaceChipsTest {
    @get:Rule
    val compose = createComposeRule()

    private val people = listOf(PersonUi(7, "Colin McFadden", "CM", me = true), PersonUi(9, "Sam B.", "SB", false), PersonUi(11, "Jess R.", "JR", false), PersonUi(12, "Kim L.", "KL", false))
    private val long = OpenPackUi("p-ex", "B CO FIELD EXERCISE NORTH GEORGIA", people, PackStatus.OFFLINE, finished = false, readOnly = false, pending = 2)

    /** The real row over a stand-in map: the folded search, the chips and the account, and the pack's line under them. */
    private fun show(pack: OpenPackUi?) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            val styles = MapStyles.available("pk.t")
            MapScreen(
                state = MapUiState(styles, styles.first()), camera = null, gps = GpsState.Off,
                onSearch = { true }, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {}, onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {},
                // Not the first face's initials, so a face drawn is the chip's.
                account = { AccountButton("Pat Doe", "pilot@example.com", "Version 1.9.0 (1)", night = false, onNight = {}, onSignOut = {}, notice = "", floating = true) },
                chips = { WorkspaceChips(pack, packsHere = true, onSwitcher = {}, onImport = {}, onLibrary = {}) },
                belowChips = { pack?.let { PackStatusLine(it) } },
                map = { Box(Modifier.fillMaxSize().background(Color(0xFF3A4A3A))) },
            )
        }
    }

    private fun onScreen(description: String) {
        val node = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$description is cut off: ${node.boundsInRoot} in $root", node.boundsInRoot.right <= root.right - 16 && node.boundsInRoot.width >= 40)
    }

    /** The name as laid out: how wide it is drawn, in dp, and whether it was cut. */
    private fun shown(name: String): Pair<Float, Boolean> {
        val node = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        return node.boundsInRoot.width / compose.density.density to layout.single().isLineEllipsized(0)
    }

    /** Whether any of the faces with these initials is drawn (a form that leaves them out still composes them, unplaced). */
    private fun faceDrawn(initials: String) = compose.onAllNodesWithText(initials, useUnmergedTree = true).fetchSemanticsNodes().any { it.layoutInfo.isPlaced }

    @Test
    fun `on a narrow phone the pack's faces and its arrow give way before its name, and the account and the search stay`() {
        show(long)
        onScreen(PackWords.chipDescription(long))
        onScreen("Account, Pat Doe")
        onScreen("Search a grid or lat/long")
        assertFalse("the faces gave way", faceDrawn("CM"))
        val (width, cut) = shown(long.name)
        assertTrue("the name keeps some of its words: $width dp", cut && width >= 56f)
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
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun `where they fit (a tablet in portrait), Import and the Library keep their words`() {
        show(null)
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("Import").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/workspace-chips-portrait-tablet.png")
    }

    @Test
    fun `a short name with people keeps its whole name, the faces giving way where they do not fit beside it`() {
        val dk = long.copy(name = "OP DK", status = PackStatus.LIVE, pending = 0)
        show(dk)
        onScreen(PackWords.chipDescription(dk))
        assertFalse("the name is whole", shown("OP DK").second)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun `on a wider phone a short name keeps its faces`() {
        val dk = long.copy(name = "OP DK", status = PackStatus.LIVE, pending = 0)
        show(dk)
        assertFalse("the name is whole", shown("OP DK").second)
        assertTrue("a face is drawn", faceDrawn("CM"))
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at the largest text nothing leaves the screen`() {
        show(long)
        onScreen("Library: everything you have saved")
        onScreen(PackWords.chipDescription(long))
        onScreen("Account, Pat Doe")
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
        assertFalse("the name is cut", shown("OP DK").second)
        compose.onRoot().captureRoboImage("build/screenshots/workspace-chips-finished-largest.png")
    }
}
