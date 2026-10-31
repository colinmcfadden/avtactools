package app.ezpztac.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the panel sheet rests and where a release lands: plain arithmetic, tried without a screen. */
class SheetAnchorsTest {
    // A phone's space above the bar: 2,500 px, the search bar and chips taking the top 420, lowered showing 250.
    private val anchors = SheetAnchors.of(heightPx = 2500f, topLimitPx = 420f, loweredHeightPx = 250f, halfFraction = 0.6f)

    @Test
    fun `lowered shows the header, half takes the given share, full stops below the top controls`() {
        assertEquals(2250f, anchors.loweredPx, 0.01f)
        assertEquals(1000f, anchors.halfPx, 0.01f)
        assertEquals(420f, anchors.fullPx, 0.01f)
        assertEquals(1500f, anchors.heightOf(SheetDetent.Half), 0.01f)
    }

    @Test
    fun `on a space too small for all three they close up rather than cross`() {
        val tiny = SheetAnchors.of(heightPx = 600f, topLimitPx = 420f, loweredHeightPx = 250f, halfFraction = 0.6f)
        assertEquals(420f, tiny.fullPx)
        assertEquals(420f, tiny.loweredPx)                                     // never above the top controls
        assertEquals(420f, tiny.halfPx)
    }

    @Test
    fun `let go still, it goes to the nearest`() {
        assertEquals(SheetDetent.Half, anchors.settleAt(1200f, 0f, flickPx = 400f))
        assertEquals(SheetDetent.Lowered, anchors.settleAt(2000f, 0f, flickPx = 400f))
    }

    @Test
    fun `a flick goes on to the next detent the way it was moving`() {
        assertEquals(SheetDetent.Full, anchors.settleAt(950f, -900f, flickPx = 400f))     // just past half, flicked up
        assertEquals(SheetDetent.Lowered, anchors.settleAt(1050f, 900f, flickPx = 400f))  // just past half, flicked down
    }

    @Test
    fun `a slow release goes on once it has come half way, and back if not`() {
        assertEquals(SheetDetent.Half, anchors.settleAt(1400f, 100f, flickPx = 400f))     // from half towards lowered, under half way
        assertEquals(SheetDetent.Lowered, anchors.settleAt(1700f, 100f, flickPx = 400f))  // past half way
        assertEquals(SheetDetent.Half, anchors.settleAt(1500f, -100f, flickPx = 400f))    // moving up from lowered, past half way
        assertEquals(SheetDetent.Lowered, anchors.settleAt(1700f, -100f, flickPx = 400f)) // moving up from lowered, under half way
    }

    @Test
    fun `at the top a fling up stays at the top`() {
        assertEquals(SheetDetent.Full, anchors.settleAt(420f, -2000f, flickPx = 400f))
    }

    @Test
    fun `a tap on the handle steps up, and from the top back down`() {
        assertEquals(SheetDetent.Half, PanelSheetState(SheetDetent.Lowered).stepUp())
        assertEquals(SheetDetent.Full, PanelSheetState(SheetDetent.Half).stepUp())
        assertEquals(SheetDetent.Lowered, PanelSheetState(SheetDetent.Full).stepUp())
        assertEquals(SheetDetent.Full, PanelSheetState(SheetDetent.Half).fullOrHalf())
        assertEquals(SheetDetent.Half, PanelSheetState(SheetDetent.Full).fullOrHalf())
    }
}
