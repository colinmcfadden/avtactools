package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.sync.SyncStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library drawn, beside the design's AP-Library and AP-Confirm (A), and on a 360 dp phone with large text: `-Pezpz.screenshots` writes them to
 * build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class LibraryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        LibraryRowUi("lz-1", LibraryTab.LZ, "LZ HAWK (ROCK)", grid = "16S GC 31204 58811", updated = "Updated Oct 3 · 09:12", inSession = true),
        LibraryRowUi("lz-2", LibraryTab.LZ, "LZ HAWK (SHOPE)", grid = "16S GC 24118 57290", updated = "Updated Aug 25 · 17:34"),
        LibraryRowUi("lz-3", LibraryTab.LZ, "LZ RAZORBILL", grid = "16S GC 22906 55120", updated = "Updated Aug 25 · 17:27"),
        LibraryRowUi(
            "lz-4", LibraryTab.LZ, "LZ FALCON (from this device, 14:32)", grid = "16S GC 27743 51866", updated = "Updated Aug 25 · 17:16",
            sync = SyncStatus.CONFLICT, conflictOf = "lz-9",
        ),
    )
    private val state = LibraryUiState(rows = rows, counts = mapOf(LibraryTab.LZ to 12, LibraryTab.ROUTES to 4, LibraryTab.POINTS to 3))

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { EzpzTheme(mode) { content() } }
        compose.onRoot().captureRoboImage("build/screenshots/library-$name.png")
    }

    @Test fun lz() = shot("lz") { LibraryPage(state, LibraryActions()) }

    @Test fun routesNight() = shot("routes-night", ThemeMode.Night) {
        LibraryPage(
            LibraryUiState(
                tab = LibraryTab.ROUTES, counts = state.counts,
                rows = listOf(
                    LibraryRowUi("r1", LibraryTab.ROUTES, "NIGHT INFIL", detail = "Sketched routes · 3 routes", updated = "Updated Oct 3 · 09:12", inSession = true),
                    LibraryRowUi("m1", LibraryTab.ROUTES, "GOAT SUCKER", detail = "Mission file · GOAT_SUCKER.msnx", updated = "Updated Aug 25 · 17:34", isMission = true),
                ),
            ),
            LibraryActions(),
        )
    }

    @Test fun searchLight() = shot("search-light", ThemeMode.Light) { LibraryPage(state.copy(query = "zz", rows = emptyList()), LibraryActions()) }

    @Test fun rename() = shot("rename") {
        Box(Modifier.padding(24.dp)) { LibraryRenameContent(rows[1], listOf("lz-1" to "LZ HAWK (ROCK)", "lz-3" to "LZ RAZORBILL"), { _, _ -> }, {}) }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
    fun narrow() {
        shot("narrow") { LibraryPage(state, LibraryActions()) }
        // Each tab's count is on the screen, and a grid on one line, not split into two values.
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        for (count in listOf("12", "4", "3")) {
            val node = compose.onAllNodesWithText(count, useUnmergedTree = true).fetchSemanticsNodes().single()
            assertTrue("the count $count is cut off", node.boundsInRoot.right <= width)
            assertTrue("the count $count is squeezed", node.boundsInRoot.width > 0f)
        }
        for (grid in listOf("16S GC 31204 58811", "16S GC 24118 57290")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onAllNodesWithText(grid, useUnmergedTree = true).fetchSemanticsNodes().single().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            assertEquals("$grid is split", 1, layout.single().lineCount)
        }
        // The tabs' words and the chips stay whole on a narrow phone with large text.
        for (words in listOf("LZ/PZ", "Routes", "Local points", "Open in session", "Conflict copy")) {
            for (node in compose.onAllNodesWithText(words, useUnmergedTree = true).fetchSemanticsNodes()) {
                val layout = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
                val l = layout.single()
                assertFalse("\"$words\" is cut", l.isLineEllipsized(l.lineCount - 1) || l.didOverflowHeight)
            }
        }
    }
}
