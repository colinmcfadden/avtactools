package app.ezpztac.android.frame

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelSheetDefaults
import app.ezpztac.designsystem.PanelSheetState
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's frame over a stand-in map: which panel shows, the tabs and their counts, the sheet's heights, and that the map still takes a tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class PhoneFrameTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private lateinit var sheet: PanelSheetState

    private fun show(
        initial: Panel = Panel.Lz,
        detent: SheetDetent = SheetDetent.Half,
        counts: PanelCounts = PanelCounts(threats = 2, imports = 3),
        panels: List<Panel> = Panel.entries,
        onFrame: (MapFrame) -> Unit = {},
        footer: (@androidx.compose.runtime.Composable () -> Unit)? = null,
        snackbars: SnackbarHostState? = null,
    ) {
        sheet = PanelSheetState(detent)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                var chosen by remember { mutableStateOf(initial) }
                PhoneFrame(
                    panels = panels, current = shownPanel(chosen, panels), counts = counts,
                    onPick = { log += "pick ${it.label}"; chosen = it },
                    sheet = sheet, snackbars = snackbars ?: remember { SnackbarHostState() },
                    panel = { Text("Inside ${it.label}") },
                    footer = footer,
                ) { frame ->
                    onFrame(frame)
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF55703F)).clickable { log += "map" })
                        frame.sheet()
                    }
                }
            }
        }
    }

    @Test
    fun `the chosen panel shows and the others do not, though they stay alive`() {
        show(Panel.Routes)
        compose.onNodeWithText("Inside Routes").assertIsDisplayed()
        compose.onNodeWithText("Inside LZ/PZ").assertDoesNotExist()
        compose.onNodeWithText("Inside Threats").assertDoesNotExist()
    }

    @Test
    fun `a tab picks its panel, and is marked`() {
        show(Panel.Lz)
        compose.onNode(hasText("Threats") and isSelectable()).performClick()
        compose.onNodeWithText("Inside Threats").assertIsDisplayed()
        compose.onNode(hasText("Threats") and isSelectable()).assertIsSelected()
        assertEquals(listOf("pick Threats"), log)
    }

    @Test
    fun `threats and imports carry their counts, and say what they count`() {
        show()
        compose.onNodeWithContentDescription("Threats, 2 threats").assertIsDisplayed()
        compose.onNodeWithContentDescription("Imports, 3 items").assertIsDisplayed()
    }

    @Test
    fun `nothing to count carries no badge`() {
        show(counts = PanelCounts())
        compose.onNodeWithContentDescription("Threats, 0 threats").assertDoesNotExist()
        compose.onNodeWithContentDescription("Threats", substring = true).assertDoesNotExist()   // the tab says only its label
    }

    @Test
    fun `a panel whose feature is off has no tab`() {
        show(panels = listOf(Panel.Lz, Panel.Imports))
        compose.onNode(hasText("Routes") and isSelectable()).assertDoesNotExist()
        compose.onNode(hasText("Threats") and isSelectable()).assertDoesNotExist()
    }

    @Test
    fun `the header opens the panel to full height, then lowers it to half`() {
        show(Panel.Imports, SheetDetent.Half)
        compose.onNodeWithContentDescription("Open the Imports panel full height").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Full, sheet.settled)
        compose.onNodeWithContentDescription("Lower the Imports panel").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Half, sheet.settled)
    }

    @Test
    fun `the sheet does not take a tap meant for the map above it`() {
        show(detent = SheetDetent.Half)
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.12f)) }       // well above a half-height sheet
        assertEquals(listOf("map"), log)
    }

    @Test
    fun `the readout stands on the sheet's edge, and a sheet raised past half slides over it`() {
        var edge: () -> Float? = { null }
        show(detent = SheetDetent.Lowered, onFrame = { edge = it.controlsBottom })
        compose.waitForIdle()
        assertEquals(sheet.anchors!!.loweredPx, edge()!!, 0.5f)
        compose.onNodeWithContentDescription("Open the LZ/PZ panel full height").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Full, sheet.settled)
        assertEquals(sheet.anchors!!.halfPx, edge()!!, 0.5f)                                   // not the full sheet's edge, under the chips
    }

    @Test
    fun `the map's middle follows the height the sheet rests at, and stays at half's when it is full`() {
        var frame: MapFrame? = null
        show(detent = SheetDetent.Half, onFrame = { frame = it })
        compose.waitForIdle()
        val half = frame!!.padding.bottom
        val halfHeight = with(compose.density) { sheet.anchors!!.heightOf(SheetDetent.Half).toDp() }
        assertEquals(halfHeight.value, half.value, 0.5f)                                          // at half, the half sheet's height
        assertTrue(half > PanelSheetDefaults.LoweredHeight + 100.dp)                               // and not the lowered one's
        compose.onNodeWithContentDescription("Open the LZ/PZ panel full height").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Full, sheet.settled)
        assertEquals(half, frame.padding.bottom)
    }

    @Test
    fun `lowered, the map's middle is the middle of all but the sheet's header`() {
        var frame: MapFrame? = null
        show(detent = SheetDetent.Lowered, onFrame = { frame = it })
        compose.waitForIdle()
        assertEquals(PanelSheetDefaults.LoweredHeight.value, frame!!.padding.bottom.value, 0.5f)
    }

    @Test
    fun `lowered, the panel's header still shows`() {
        show(Panel.Routes, SheetDetent.Lowered)
        compose.onNodeWithContentDescription("Open the Routes panel full height").assertIsDisplayed()
    }

    @Test
    fun `at full height a toast stands above the panel's footer, not on its buttons`() {
        val toasts = SnackbarHostState()
        show(Panel.Routes, detent = SheetDetent.Full, snackbars = toasts, footer = { Box(Modifier.fillMaxWidth().height(64.dp)) { Text("Sketch a route") } })
        compose.runOnIdle { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { toasts.showSnackbar("Saved") } }
        compose.waitForIdle()
        val toast = compose.onNodeWithText("Saved").fetchSemanticsNode().boundsInRoot
        val foot = compose.onNodeWithText("Sketch a route").fetchSemanticsNode().boundsInRoot
        assertTrue("toast ${toast.bottom} over the footer's top ${foot.top}", toast.bottom <= foot.top)
    }

}
