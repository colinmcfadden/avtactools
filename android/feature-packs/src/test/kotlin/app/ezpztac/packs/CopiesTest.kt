package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.sync.RecordKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Adding a copy to a Mission Pack (the design's PackDialogs A), and the Pack panel's *Add from Library*. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class CopiesTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = AddToPackActions(choose = { log += "choose $it" }, add = { log += "add" }, retry = { log += "retry" }, dismiss = { log += "cancel" })

    private val packs = listOf(
        PackChoice("dk", "OP DK", "4 members · you can edit", usable = true, finished = false),
        PackChoice("bco", "B CO FIELD EX", "18 members · you can edit", usable = true, finished = false),
        PackChoice("razor", "OP RAZORBILL", "Finished Sep 22. Reopen it to add items.", usable = false, finished = true),
    )
    private val asked = AddToPackUi(RecordKind.LZ, "u1", "LZ HAWK (SHOPE)", "lz-a", packs = packs, chosen = "dk")

    private var shown by mutableStateOf<AddToPackUi?>(null)

    /** Shows [state]; a test that shows another later changes what is shown, in the one window a test has. */
    private fun show(state: AddToPackUi, mode: ThemeMode = ThemeMode.Dark) {
        val first = shown == null
        shown = state
        if (!first) {
            compose.waitForIdle()
            return
        }
        compose.setContent {
            EzpzTheme(mode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.Center) {
                    shown?.let { AddToPackContent(it, actions) }
                }
            }
        }
    }

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)

    @Test
    fun `the packs are radios, the one chosen marked, a finished one not to be chosen, and Add copy makes it`() {
        show(asked)
        compose.onNodeWithText("Add to a Mission Pack").assertExists()
        compose.onNodeWithText("Adding “LZ HAWK (SHOPE)”.").assertExists()
        compose.onNode(radio and hasText("OP DK", substring = true)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNode(radio and hasText("OP RAZORBILL", substring = true)).assertIsNotEnabled()
        compose.onNode(radio and hasText("B CO FIELD EX", substring = true)).performClick()
        compose.onNodeWithText(PackCopyViewModel.NOTE).assertExists()
        compose.onNode(hasText("Add copy") and hasClickAction()).performClick()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(listOf("choose bco", "add", "cancel"), log)
        compose.onRoot().captureRoboImage("build/screenshots/add-to-pack.png")
    }

    @Test
    fun `nothing chosen cannot be added, and while it is made nothing can be changed`() {
        show(asked.copy(chosen = null))
        compose.onNode(hasText("Add copy") and hasClickAction()).assertIsNotEnabled()
        show(asked.copy(sending = true))
        compose.onNode(hasText("Adding…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(radio and hasText("B CO FIELD EX", substring = true)).assertIsNotEnabled()
        compose.onNode(hasText("Close") and hasClickAction()).assertIsEnabled().performClick()       // the copy goes on; the dialog can go
        assertEquals(listOf("cancel"), log)
    }

    @Test
    fun `the packs are one group, named for a screen reader`() {
        show(asked)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Mission Pack"))).assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletShot() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), contentAlignment = Alignment.Center) {
                    AddToPackContent(asked, actions, Modifier.width(540.dp), tablet = true)
                }
            }
        }
        compose.onNode(radio and hasText("OP DK", substring = true)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onRoot().captureRoboImage("build/screenshots/add-to-pack-tablet.png")
    }

    @Test
    fun `while the packs are asked for it says so, a failure has Try again, and with none it says how to make one`() {
        show(asked.copy(packs = null, chosen = null))
        compose.onNodeWithText("Loading your packs…").assertExists()
        show(asked.copy(packs = null, chosen = null, listError = "There is no connection to the server. Try again when you are back online."))
        compose.onNode(hasText("Try again") and hasClickAction()).performClick()
        show(asked.copy(packs = emptyList(), chosen = null))
        compose.onNodeWithText(PackCopyViewModel.NO_PACKS).assertExists()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `why a copy did not go is said in the dialog`() {
        show(asked.copy(error = "The pack already has that item."))
        compose.onNodeWithText("The pack already has that item.").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `on a small phone at the largest text no pack's name or what it is to this person is cut`() {
        show(asked)
        for (words in listOf("B CO FIELD EX", "18 members · you can edit", "Finished Sep 22. Reopen it to add items.", "Add copy")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
        }
        compose.onRoot().captureRoboImage("build/screenshots/add-to-pack-narrow.png")
    }

    @Test
    fun lightShot() {
        show(asked, ThemeMode.Light)
        compose.onRoot().captureRoboImage("build/screenshots/add-to-pack-light.png")
    }

    // -- The Pack panel's foot -------------------------------------------------------------------------------------------

    @Test
    fun `the foot adds from the Library for anyone who can change the pack, and finishes it for its owner`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column(Modifier.width(412.dp)) { PackPanelFoot(onFinish = { log += "finish" }, onAddFromLibrary = { log += "library" }) }
            }
        }
        val add = compose.onNodeWithContentDescription("Add from Library, copies of your own items").fetchSemanticsNode().boundsInRoot
        val finish = compose.onNodeWithContentDescription("Finish pack, makes it read-only for everyone").fetchSemanticsNode().boundsInRoot
        val row = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Add from Library on the left, Finish at the end: $add $finish $row", add.left < 100f && finish.right > row.right - 100f)
        compose.onNodeWithContentDescription("Add from Library, copies of your own items").performClick()
        compose.onNodeWithContentDescription("Finish pack, makes it read-only for everyone").performClick()
        compose.onRoot().captureRoboImage("build/screenshots/pack-foot.png")
        assertEquals(listOf("library", "finish"), log)
    }

    @Test
    fun `an owner's foot with nothing to add keeps Finish at the end`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Column(Modifier.width(412.dp)) { PackPanelFoot(onFinish = { log += "finish" }) } } }
        val finish = compose.onNodeWithContentDescription("Finish pack, makes it read-only for everyone").fetchSemanticsNode().boundsInRoot
        val row = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$finish $row", finish.right > row.right - 100f && finish.left > 300f)
    }

    @Test
    fun `an editor's foot adds from the Library only`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Column(Modifier.width(412.dp)) { PackPanelFoot(onFinish = null, onAddFromLibrary = { log += "library" }) } } }
        compose.onNodeWithContentDescription("Finish pack, makes it read-only for everyone").assertDoesNotExist()
        val add = compose.onNodeWithContentDescription("Add from Library, copies of your own items").fetchSemanticsNode().boundsInRoot
        assertTrue("an editor's Add from Library starts the row: $add", add.left < 100f)
        compose.onNodeWithContentDescription("Add from Library, copies of your own items").assertIsEnabled().performClick()
        assertEquals(listOf("library"), log)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `at the largest text on a small phone the foot's buttons go one under the other, neither cut`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Column(Modifier.width(360.dp)) { PackPanelFoot(onFinish = {}, onAddFromLibrary = {}) } } }
        val add = compose.onNodeWithContentDescription("Add from Library, copies of your own items").fetchSemanticsNode().boundsInRoot
        val finish = compose.onNodeWithContentDescription("Finish pack, makes it read-only for everyone").fetchSemanticsNode().boundsInRoot
        assertTrue("$add $finish", finish.top >= add.bottom)
        for (words in listOf("Add from Library", "Finish pack")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
        }
    }
}
