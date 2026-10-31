package app.ezpztac.planning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouteColorsTest {
    @Test
    fun `the palette is the web's eight colours, in order`() {
        assertEquals(8, RouteColors.PALETTE.size)
        assertEquals("#FF453A", RouteColors.PALETTE.first())
        assertEquals("#FF375F", RouteColors.PALETTE.last())
        assertEquals(RouteColors.PALETTE.size, RouteColors.PALETTE.toSet().size)
    }

    @Test
    fun `the first route is red and each next one takes the next colour`() {
        assertEquals("#FF453A", RouteColors.next(emptyList()))
        assertEquals("#0A84FF", RouteColors.next(listOf("#FF453A")))
        assertEquals("#32D74B", RouteColors.next(listOf("#FF453A", "#0A84FF")))
    }

    @Test
    fun `a colour that was freed is used again before a new one`() {
        assertEquals("#FF453A", RouteColors.next(listOf("#0A84FF", "#32D74B")))
        assertEquals("#0A84FF", RouteColors.next(listOf("#FF453A", "#32D74B")))
    }

    @Test
    fun `a colour is recognised however its hex is written`() {
        assertEquals("#0A84FF", RouteColors.next(listOf("#ff453a")))
    }

    @Test
    fun `a colour of its own is not one of the palette's, so it takes nothing away`() {
        assertEquals("#FF453A", RouteColors.next(listOf("#123456")))
    }

    @Test
    fun `when all eight are used it starts over by how many there are`() {
        assertEquals("#FF453A", RouteColors.next(RouteColors.PALETTE))
        assertEquals("#0A84FF", RouteColors.next(RouteColors.PALETTE + "#123456"))
    }
}
