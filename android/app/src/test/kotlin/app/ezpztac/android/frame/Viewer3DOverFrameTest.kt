package app.ezpztac.android.frame

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import app.ezpztac.data.LidarPhase
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelSheetState
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.viewer3d.Viewer3DActions
import app.ezpztac.viewer3d.Viewer3DPage
import app.ezpztac.viewer3d.Viewer3DUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 3D view over the phone's frame as MapHome lays them out (the owner's: the whole screen, closing back to the map exactly as it was): the frame is
 * left composed under it, out of a screen reader's reach and out of touch's while 3D is up, and comes back with its sheet, panel and typed text as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class Viewer3DOverFrameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `3D over the frame leaves the sheet, the panel, what was typed and what the panel remembered as they were`() {
        val log = mutableListOf<String>()
        val sheet = PanelSheetState(SheetDetent.Half)
        val frameState = FrameState().apply { choose(Panel.Lz) }
        var threeD by mutableStateOf(false)
        var builds = 0
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    val panels = Panel.entries - Panel.Pack
                    PhoneFrame(
                        panels = panels, current = shownPanel(frameState.chosen, panels), counts = PanelCounts(),
                        onPick = { frameState.choose(it) }, sheet = sheet, snackbars = remember { SnackbarHostState() },
                        modifier = if (threeD) Modifier.clearAndSetSemantics {} else Modifier,
                        panel = { p ->
                            // Made once for the life of the panel: built again, it would count again.
                            remember { ++builds }
                            var typed by rememberSaveable { mutableStateOf("") }
                            var taps by remember { mutableIntStateOf(0) }
                            Column {
                                Text("Inside ${p.label}, tapped $taps", Modifier.clickable { taps++ })
                                BasicTextField(typed, { typed = it })
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF55703F)).clickable { log += "map" })
                    }
                    if (threeD) Viewer3DPage(Viewer3DUi("LZ HAWK", LidarPhase.AVAILABLE), Viewer3DActions(close = { threeD = false }))
                }
            }
        }
        compose.onNodeWithText("Inside LZ/PZ", substring = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("LZ RAVEN")
        val made = builds
        compose.runOnIdle { threeD = true }
        compose.waitForIdle()
        // Up: the frame is neither read nor touched.
        compose.onNodeWithText("3D · LZ HAWK").assertIsDisplayed()
        compose.onNodeWithText("Inside LZ/PZ", substring = true).assertDoesNotExist()
        compose.onRoot().performTouchInput { click(center) }
        assertEquals(emptyList<String>(), log)
        compose.onNodeWithContentDescription("Close 3D view").performClick()
        compose.waitForIdle()
        // Closed: everything as it was, nothing built again.
        compose.onNode(hasText("Inside LZ/PZ, tapped 1")).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("LZ RAVEN")).assertIsDisplayed()
        assertEquals(made, builds)
        assertEquals(SheetDetent.Half, sheet.settled)
        assertEquals(Panel.Lz, frameState.chosen)
        assertTrue(!frameState.hidden && !frameState.closed)
    }
}
