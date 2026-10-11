package app.ezpztac.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The panel sheet under a finger: what [PanelSheetState.heading] says while it settles, and that a change of space while it moves does not leave it taking
 * every touch. Both were found by a review's probes (the second left nothing in the sheet tappable).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class PanelSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private var height by mutableStateOf<Dp?>(null)
    private val clicks = mutableListOf<String>()

    private fun show(state: PanelSheetState) {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(height?.let { Modifier.fillMaxWidth().height(it) } ?: Modifier.fillMaxSize()) {
                    PanelSheet(state, topLimit = 120.dp, header = { PanelHeader("Sheet header") }) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Button(onClick = { clicks += "button" }) { Text("A button") }
                            repeat(40) { Text("row $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Drags the header up most of the way from lowered to half, then lets go. */
    private fun dragUpAndLetGo(state: PanelSheetState) {
        val a = state.anchors!!
        compose.onNodeWithText("Sheet header").performTouchInput {
            down(center)
            val distance = (a.loweredPx - a.halfPx) * 0.7f
            repeat(14) { moveBy(Offset(0f, -distance / 14), delayMillis = 40) }
            up()
        }
    }

    @Test
    fun `once a finger lets go, heading is where the sheet settles, while it is still moving`() {
        val state = PanelSheetState(SheetDetent.Lowered)
        show(state)
        val a = state.anchors!!
        compose.mainClock.autoAdvance = false
        dragUpAndLetGo(state)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("still moving", state.offsetPx > a.halfPx + 1 && state.offsetPx < a.loweredPx - 1)
        assertEquals(SheetDetent.Half, state.heading)                                            // the map's middle moves with the settle
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(SheetDetent.Half, state.settled)
    }

    @Test
    fun `while a finger holds the sheet, heading stays where it last rested`() {
        val state = PanelSheetState(SheetDetent.Lowered)
        show(state)
        val a = state.anchors!!
        compose.onNodeWithText("Sheet header").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -(a.loweredPx - a.halfPx) * 0.08f), delayMillis = 40) }
        }
        compose.waitForIdle()
        assertEquals(SheetDetent.Lowered, state.heading)
        compose.onNodeWithText("Sheet header").performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun `a change of space while the sheet settles leaves it at rest, and what is in it can still be tapped`() {
        height = 891.dp
        val state = PanelSheetState(SheetDetent.Lowered)
        show(state)
        compose.mainClock.autoAdvance = false
        dragUpAndLetGo(state)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        height = 700.dp                                                                           // a turn of the phone, split screen
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(false, state.draggable.isAnimationRunning)
        assertEquals(SheetDetent.Half, state.settled)
        assertEquals(state.anchors!!.halfPx, state.offsetPx, 0.5f)                               // at the new half, not the old one
        compose.onNodeWithText("A button").performClick()
        assertEquals(listOf("button"), clicks)
    }
}
