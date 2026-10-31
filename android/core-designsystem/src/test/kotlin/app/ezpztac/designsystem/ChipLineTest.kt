package app.ezpztac.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A chip's line on the phone's row, given less and less room: what only adds gives way (the extras, fullest first; then the arrow; then the lead, where it may)
 * before the name is cut, and the name is cut only when nothing else is left to give.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class ChipLineTest {
    @get:Rule
    val compose = createComposeRule()

    private fun part(name: String, width: Dp) = @androidx.compose.runtime.Composable { Box(Modifier.size(width, 20.dp).semantics { contentDescription = name }) }

    private data class Setting(val room: Dp, val extrasMayGo: Boolean, val leadMayGo: Boolean)

    private var setting by mutableStateOf<Setting?>(null)

    /** What is drawn, left to right, with how wide the name is, in a line [room] wide (gaps of 6 dp): lead 18, name 100, faces 64 or 24, arrow 18. */
    private fun drawn(room: Dp, extrasMayGo: Boolean = true, leadMayGo: Boolean = false): Pair<List<String>, Float> {
        if (setting == null) {
            compose.setContent {
                setting?.let { s ->
                    Box(Modifier.width(s.room)) {
                        ChipLine(
                            lead = part("lead", 18.dp), name = part("name", 100.dp), extras = listOf(part("three", 64.dp), part("one", 24.dp)), arrow = part("arrow", 18.dp),
                            extrasMayGo = s.extrasMayGo, leadMayGo = s.leadMayGo,
                        )
                    }
                }
            }
        }
        setting = Setting(room, extrasMayGo, leadMayGo)
        compose.waitForIdle()
        val nodes = listOf("lead", "name", "three", "one", "arrow").mapNotNull { name ->
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(name)).fetchSemanticsNodes().firstOrNull { it.layoutInfo.isPlaced }?.let { name to it.boundsInRoot }
        }
        val order = nodes.sortedBy { it.second.left }.map { it.first }
        return order to (nodes.firstOrNull { it.first == "name" }?.second?.width ?: 0f)
    }

    @Test
    fun `with room for all of it, all of it is drawn`() {
        // 18 + 6 + 100 + 6 + 64 + 6 + 18 = 218
        assertEquals(listOf("lead", "name", "three", "arrow") to 100f, drawn(218.dp))
    }

    @Test
    fun `the extras give way fullest first, then the arrow, before the name is cut`() {
        assertEquals(listOf("lead", "name", "one", "arrow") to 100f, drawn(217.dp))                // 178 fits
        assertEquals(listOf("lead", "name", "arrow") to 100f, drawn(177.dp))                       // 148 fits
        assertEquals(listOf("lead", "name") to 100f, drawn(147.dp))                                // 124 fits
        assertEquals(listOf("lead", "name") to 76f, drawn(100.dp))                                 // nothing left to give: the name is cut
    }

    @Test
    fun `a last extra that may not go stays, and a lead that may goes after the arrow`() {
        assertEquals(listOf("lead", "name", "one") to 100f, drawn(154.dp, extrasMayGo = false))  // 18 + 6 + 100 + 6 + 24 = 154
        assertEquals(listOf("name", "one") to 100f, drawn(153.dp, extrasMayGo = false, leadMayGo = true))
        assertEquals(listOf("name", "one") to 70f, drawn(100.dp, extrasMayGo = false, leadMayGo = true))
    }
}
