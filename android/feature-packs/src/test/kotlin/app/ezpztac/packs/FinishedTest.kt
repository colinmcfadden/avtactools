package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.missionpacks.PackStatus
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A finished pack where the person works (the design's Finished), and finishing, reopening and duplicating one. `-Pezpz.screenshots` draws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class FinishedTest {
    @get:Rule
    val compose = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private val people = listOf(PersonUi(7, "Colin McFadden", "CM", me = true), PersonUi(9, "Sam Bell", "SB", me = false))
    private val finished = OpenPackUi(
        "p", "OP DK", people, PackStatus.LIVE, finished = true, readOnly = true, pending = 0, role = "owner", finishedBy = "Colin McFadden",
        finishedAt = "2026-10-05T14:20:00",
    )
    private val first = "OP DK was finished by Colin McFadden on Oct 5 at 14:20."
    private val log = mutableListOf<String>()

    private fun banner(pack: OpenPackUi = finished, mode: ThemeMode = ThemeMode.Dark) = compose.setContent {
        EzpzTheme(mode) {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
                FinishedBanner(pack, onDuplicate = { log += "duplicate" }, onReopen = { log += "reopen" }, zone = utc, now = now)
            }
        }
    }

    @Test
    fun `the banner says who finished it and when, and the owner may reopen it and anyone duplicate it`() {
        banner()
        compose.onNodeWithText("$first It is read-only for everyone.").assertIsDisplayed()
        compose.onNodeWithText("You own OP DK, so only you can reopen it.").assertIsDisplayed()
        compose.onNode(hasText("Reopen pack") and hasClickAction()).performClick()
        compose.onNode(hasText("Duplicate as new pack") and hasClickAction()).performClick()
        assertEquals(listOf("reopen", "duplicate"), log)
        // Said again to a screen reader when it changes.
        val node = compose.onNodeWithText("$first It is read-only for everyone.").fetchSemanticsNode()
        assertTrue(SemanticsProperties.LiveRegion in node.config)
    }

    @Test
    fun `someone who does not own it is offered a copy, not a reopen`() {
        banner(finished.copy(role = "editor", finishedBy = null, finishedAt = null))
        compose.onNodeWithText("OP DK was finished by its owner. It is read-only for everyone.").assertIsDisplayed()
        compose.onNodeWithText("Reopen pack").assertDoesNotExist()
        compose.onNodeWithText("You own OP DK", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Duplicate as new pack") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `on a small phone at the largest text no word of the banner is cut`() {
        banner()
        for (words in listOf("$first It is read-only for everyone.", "Duplicate as new pack", "Reopen pack", "You own OP DK, so only you can reopen it.")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
        }
    }

    @Test
    fun `the tablet's band says the same in two lines, with the owner's reopen and a copy for anyone`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column {
                    FinishedBand(finished, onDuplicate = { log += "duplicate" }, onReopen = { log += "reopen" }, zone = utc, now = now)
                    FinishedBand(finished.copy(uuid = "q", role = "viewer"), onDuplicate = {}, onReopen = { log += "not offered" }, zone = utc, now = now)
                }
            }
        }
        assertEquals(2, compose.onAllNodesWithText(first).fetchSemanticsNodes().size)
        compose.onNode(hasText("Reopen pack") and hasClickAction()).performClick()                // the owner's band only
        assertEquals(listOf("reopen"), log)
        assertEquals(2, compose.onAllNodesWithText("Duplicate as new pack").fetchSemanticsNodes().size)
    }

    @Test
    fun `finishing and reopening are asked about in the web's words, both choices plain`() {
        var dialog by androidx.compose.runtime.mutableStateOf<PackDialog?>(PackDialog.Finish("p", "OP DK"))
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                PackDialogs(dialog, onFinish = { log += "finish" }, onReopen = { log += "reopen" }, onDuplicate = { log += "duplicate $it" }, onDismiss = { log += "dismiss" })
            }
        }
        compose.onNodeWithText("Finish OP DK?").assertIsDisplayed()
        compose.onNodeWithText(FINISH_WORDS).assertIsDisplayed()
        compose.onNodeWithText("Finish pack").performClick()
        compose.onNodeWithText("Cancel").performClick()
        dialog = PackDialog.Reopen("p", "OP DK")
        compose.waitForIdle()
        compose.onNodeWithText("Reopen OP DK?").assertIsDisplayed()
        compose.onNodeWithText("Editors can change it again. The history keeps when it was finished.").assertIsDisplayed()
        compose.onNodeWithText("Reopen pack").performClick()
        assertEquals(listOf("finish", "dismiss", "reopen"), log)
    }

    private fun duplicate(dialog: PackDialog.Duplicate) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) { DuplicateContent(dialog, onDuplicate = { log += "duplicate $it" }, onDismiss = { log += "dismiss" }) }
    }

    @Test
    fun `the copy is named in a field that starts with its name, and what will not do is said under it`() {
        duplicate(PackDialog.Duplicate("p", "OP DK", "OP DK 2", error = "Give it a name."))
        compose.onNodeWithText(DUPLICATE_TITLE).assertIsDisplayed()
        compose.onNodeWithText("A new pack with copies of everything in OP DK. You are its owner; nobody else is in it yet.").assertIsDisplayed()
        compose.onNodeWithText("Give it a name.").assertIsDisplayed()
        compose.onNodeWithText("OP DK 2").performTextReplacement("OP EAGLE")
        compose.onNode(hasText("Duplicate") and hasClickAction()).performClick()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(listOf("duplicate OP EAGLE", "dismiss"), log)
    }

    @Test
    fun `the field takes at most a hundred characters, counting an emoji as one`() {
        duplicate(PackDialog.Duplicate("p", "OP DK", ""))
        val heli = String(Character.toChars(0x1F681))
        compose.onNode(hasText("Name")).performTextInput(heli.repeat(100))
        compose.onNode(hasText("Name")).performTextInput("X")
        val typed = compose.onNode(hasText("Name")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(heli.repeat(100), typed)
    }

    @Test
    fun `while the copy is made nothing else can be asked`() {
        duplicate(PackDialog.Duplicate("p", "OP DK", "OP DK 2", sending = true))
        compose.onNode(hasText("Duplicating…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Cancel") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `the owner's foot finishes the pack, and the header's menu duplicates it`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column(Modifier.width(412.dp)) {
                    PackHeaderMenu("OP DK", onDuplicate = { log += "duplicate" })
                    PackPanelFoot(onFinish = { log += "finish" })
                }
            }
        }
        compose.onNodeWithContentDescription("Finish pack, makes it read-only for everyone").performClick()
        compose.onNodeWithContentDescription("More actions for OP DK").performClick()
        compose.onNodeWithText("Duplicate as a new pack").performClick()
        assertEquals(listOf("finish", "duplicate"), log)
    }

    @Test
    fun `a finished pack's chip says so and shows nobody's face, and its status line says it is read-only`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column {
                    PackChip(finished, onClick = { log += "switcher" })
                    PackStatusLine(finished)
                }
            }
        }
        compose.onNodeWithContentDescription("Workspace: Mission Pack OP DK, finished and read-only. Change workspace").assertIsEnabled().performClick()
        assertEquals(listOf("switcher"), log)
        compose.onNodeWithText("Read-only for everyone").assertIsDisplayed()
        // The chip's words are one description; the faces are not drawn at all.
        compose.onNodeWithText("CM", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("· Finished", useUnmergedTree = true).assertExists()
        assertEquals("Read-only for everyone", PackWords.status(finished))
        assertEquals("Workspace: Mission Pack OP DK, finished. Change workspace", PackWords.railDescription(finished))   // the tablet design's rail tile
        assertEquals(PackWords.chipDescription(finished.copy(finished = false)), PackWords.railDescription(finished.copy(finished = false)))
        assertEquals("Cannot reach the pack", PackWords.status(finished.copy(status = PackStatus.ERROR)))
    }

    @Test
    fun `a name typed again lets go of what was said, which a screen reader is told of when it comes`() {
        var edited = 0
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                DuplicateContent(PackDialog.Duplicate("p", "OP DK", "OP DK 2", error = LOST_COPY), onDuplicate = {}, onDismiss = {}, onEdited = { edited++ })
            }
        }
        assertTrue(SemanticsProperties.LiveRegion in compose.onNodeWithText(LOST_COPY, useUnmergedTree = true).fetchSemanticsNode().config)
        compose.onNodeWithText("OP DK 2").performTextReplacement("OP EAGLE")
        assertEquals(1, edited)
    }

    @Test
    fun `while the copy is made its name stays in the field, read only, not taken from the person`() {
        duplicate(PackDialog.Duplicate("p", "OP DK", "OP DK 2", sending = true))
        compose.onNode(hasText("OP DK 2") and hasSetTextAction()).assertDoesNotExist()             // read only: nothing typed is taken
        compose.onNodeWithText("OP DK 2").assertIsEnabled()                                         // but not disabled, so it keeps its focus
    }

    @Test
    fun `the tablet's foot is the design's outlined button, saying the same`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { PackPanelFoot(onFinish = { log += "finish" }, tablet = true) } }
        compose.onNodeWithContentDescription("Finish pack, makes it read-only for everyone").performClick()
        assertEquals(listOf("finish"), log)
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp-xhdpi", fontScale = 2.0f)
    fun `on a narrow tablet at the largest text the band puts its buttons under its words, which keep their room`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { FinishedBand(finished, {}, {}, Modifier.width(744.dp), zone = utc, now = now) } }
        val words = compose.onNodeWithText(first, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val reopen = compose.onNode(hasText("Reopen pack") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("the buttons go under the words: $words, $reopen", reopen.top >= words.bottom)
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(first, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertTrue("${layout.single().lineCount} lines", layout.single().lineCount <= 3)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a wide tablet the band's buttons stand beside its words`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { FinishedBand(finished, {}, {}, Modifier.width(1184.dp), zone = utc, now = now) } }
        val words = compose.onNodeWithText(first, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val reopen = compose.onNode(hasText("Reopen pack") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("beside: $words, $reopen", reopen.left >= words.right && reopen.top < words.bottom)
    }

    // -- Pictures ------------------------------------------------------------------------------------------------------------------

    @Test fun bannerShot() = shotOf("finished-banner") { FinishedBanner(finished, {}, {}, zone = utc, now = now) }

    @Test fun bannerNightShot() = shotOf("finished-banner-night", ThemeMode.Night) { FinishedBanner(finished, {}, {}, zone = utc, now = now) }

    @Test fun bannerLightShot() = shotOf("finished-banner-light", ThemeMode.Light) { FinishedBanner(finished.copy(role = "editor"), {}, {}, zone = utc, now = now) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun bandShot() = shotOf("finished-band") { FinishedBand(finished, {}, {}, Modifier.fillMaxWidth(), zone = utc, now = now) }

    @Test
    fun chipShot() = shotOf("finished-chip") {
        PackChip(finished, onClick = {})
        Spacer(Modifier.padding(4.dp))
        PackStatusLine(finished)
        Spacer(Modifier.padding(4.dp))
        PackPanelFoot(onFinish = {})
    }

    @Test fun duplicateShot() = shotOf("duplicate-dialog") { DuplicateContent(PackDialog.Duplicate("p", "OP DK", "OP DK 2"), {}, {}) }

    private fun shotOf(name: String, mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }
}
