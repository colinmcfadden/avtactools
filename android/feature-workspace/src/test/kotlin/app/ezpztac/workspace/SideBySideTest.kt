package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two things side by side, or one under the other where the second would be squeezed: the gap is between two things, never under one alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class SideBySideTest {
    @get:Rule
    val compose = createComposeRule()

    private fun lay(flexible: Boolean) = compose.setContent {
        SideBySide(
            minFlexible = 112.dp, gap = 8.dp, flexibleFirst = false, modifier = Modifier.width(100.dp).testTag("row"),
            fixed = { Box(Modifier.size(90.dp, 40.dp)) },
            flexible = { if (flexible) Box(Modifier.size(50.dp, 20.dp)) },
        )
    }

    @Test
    fun `words that do not fit beside the buttons go under them, a gap between`() {
        lay(flexible = true)
        compose.onNodeWithTag("row").assertHeightIsEqualTo(68.dp)
    }

    @Test
    fun `with nothing beside them the buttons stand alone, with no gap under them (a pack's card)`() {
        lay(flexible = false)
        compose.onNodeWithTag("row").assertHeightIsEqualTo(40.dp)
    }
}
