package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.model.ThreatDraft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ThreatsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: ThreatsUiState, actions: ThreatsActions = ThreatsActions()) {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ThreatsContent(state, actions) }
            }
        }
    }

    @Test
    fun `an empty picture explains how to add or import and that it stays local`() {
        show(ThreatsUiState())
        compose.onNodeWithText("No threats in the picture. Move the map centre to a position and add one, or import an AMPS .ths file.").assertIsDisplayed()
        compose.onNodeWithText("Threats are never synced or saved to the server. The sealed device copy is wiped at sign-out and 48 hours after its last change.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a threat card can be held hidden and opened for editing`() {
        val calls = mutableListOf<String>()
        val row = ThreatRowUi("t1", "SA-8", "SHGPEWRR------", "16S GC 12345 67890", "25 nmi / 15 nmi", visible = true, held = true)
        show(
            ThreatsUiState(threats = listOf(row)),
            ThreatsActions(
                select = { calls += "select:$it" }, toggleVisible = { calls += "visible:$it" }, beginEdit = { calls += "edit:$it" },
            ),
        )
        compose.onNodeWithText("SA-8").performClick()
        compose.onNodeWithContentDescription("Hide SA-8 on the map").performClick()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        assertEquals(listOf("select:t1", "visible:t1", "edit:t1"), calls)
        compose.onNodeWithText("Share .ths").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the form offers both radars and sends save and cancel actions`() {
        val calls = mutableListOf<String>()
        show(
            ThreatsUiState(editing = ThreatEditUi(null, LatLon(34.75, -84.05), ThreatDraft.forNew(0))),
            ThreatsActions(saveEdit = { calls += "save" }, cancelEdit = { calls += "cancel" }),
        )
        compose.onNodeWithText("Detection radar").assertIsDisplayed()
        compose.onNodeWithText("Engagement radar").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Add threat")[1].performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("save", "cancel"), calls)
    }
}
