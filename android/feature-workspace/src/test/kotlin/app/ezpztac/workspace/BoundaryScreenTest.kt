package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drawing a boundary as a person meets it: the sheet's start button, and the toolbar over the map. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class BoundaryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        val actions = BoundaryActions(
            start = { log += "start" }, addAtCrosshair = { log += "add" }, undoPoint = { log += "undo" },
            finish = { log += "finish" }, cancel = { log += "cancel" }, dismissError = { log += "dismiss" },
        )
    }

    private fun section(state: BoundaryUiState): Recorder {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { BoundarySection(state, r.actions) } } }
        return r
    }

    private fun toolbar(state: BoundaryUiState): Recorder {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { BoundaryToolbar(state, r.actions) } } }
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val ready = BoundaryUiState(canStart = true)

    // -- The sheet --------------------------------------------------------------------------------------------------

    @Test
    fun `the sheet offers to draw a boundary, and a tap starts it`() {
        val r = section(ready)
        compose.onNodeWithText("Boundary").assertIsDisplayed()
        compose.onNodeWithText("Draw the landing area yourself", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Draw boundary").assertIsEnabled().performClick()
        assertEquals(listOf("start"), r.log)
    }

    @Test
    fun `drawing over an analysis asks first, and only the answer starts it`() {
        val r = section(ready.copy(clears = true))
        compose.onNodeWithText("Draw boundary").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Drawing a new boundary clears this analysis", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Draw boundary").performClick()
        compose.onNodeWithText("Draw").performClick()
        assertEquals(listOf("start"), r.log)
        assertEquals(0, count("Drawing a new boundary clears this analysis", substring = true))      // the question is closed once answered
        compose.onNodeWithText("Draw boundary").assertIsDisplayed()
    }

    @Test
    fun `a boundary already drawn is said, and can be drawn again`() {
        section(ready.copy(clears = true, drawnPoints = 4))
        compose.onNodeWithText("Drawn boundary: 4 points. Analyze to measure it.").assertIsDisplayed()
        compose.onNodeWithText("Draw it again").assertIsDisplayed()
    }

    @Test
    fun `when drawing cannot start the button is off and the reason is given`() {
        val r = section(BoundaryUiState(cannotStart = "Wait for the analysis to finish, or stop it, before drawing a boundary."))
        compose.onNodeWithText("Draw boundary").assertIsNotEnabled()
        compose.onNodeWithText("Wait for the analysis to finish", substring = true).assertIsDisplayed()
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `while drawing the sheet says where the buttons are and offers no second start`() {
        section(BoundaryUiState(drawing = true, points = 1))
        compose.onNodeWithText("Drawing a boundary: 1 point.", substring = true).assertIsDisplayed()
        assertEquals(0, count("Draw boundary"))
    }

    @Test
    fun `an error in the sheet can be dismissed`() {
        val r = section(ready.copy(error = "Open a diagram first."))
        compose.onNodeWithText("Open a diagram first.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }

    // -- The toolbar ------------------------------------------------------------------------------------------------

    @Test
    fun `the toolbar counts the corners and says three are needed`() {
        toolbar(BoundaryUiState(drawing = true, points = 2))
        compose.onNodeWithText("Boundary: 2 points").assertIsDisplayed()
        compose.onNodeWithText("A boundary needs 3.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Finish").assertIsNotEnabled()
        compose.onNodeWithText("Undo").assertIsEnabled()
    }

    @Test
    fun `with none put down there is nothing to undo`() {
        toolbar(BoundaryUiState(drawing = true, points = 0))
        compose.onNodeWithText("Boundary: 0 points").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsNotEnabled()
    }

    @Test
    fun `with three the boundary can be finished`() {
        val r = toolbar(BoundaryUiState(drawing = true, points = 3, canFinish = true))
        compose.onNodeWithText("Tap the map for another corner, or Finish.").assertIsDisplayed()
        compose.onNodeWithText("Finish").assertIsEnabled().performClick()
        assertEquals(listOf("finish"), r.log)
    }

    @Test
    fun `the toolbar's buttons do what they say`() {
        val r = toolbar(BoundaryUiState(drawing = true, points = 1))
        compose.onNodeWithText("Add here").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("add", "undo", "cancel"), r.log)
    }

    @Test
    fun `one point is said in the singular`() {
        toolbar(BoundaryUiState(drawing = true, points = 1))
        compose.onNodeWithText("Boundary: 1 point").assertIsDisplayed()
    }

    @Test
    fun `with nothing being drawn there is no toolbar to tap`() {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box { BoundaryToolbarWhileDrawing(ready, r.actions) } } }
        assertEquals(0, count("Add here"))
    }

    @Test
    fun `while a boundary is being drawn the toolbar is there`() {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box { BoundaryToolbarWhileDrawing(BoundaryUiState(drawing = true, points = 1), r.actions) } } }
        compose.onNodeWithText("Add here").assertIsDisplayed()
    }

    @Test
    fun `an error over the map can be dismissed`() {
        val r = toolbar(BoundaryUiState(drawing = true, points = 1, error = "Move the map to where the corner should go first."))
        compose.onNodeWithText("Move the map to where the corner should go first.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }
}
