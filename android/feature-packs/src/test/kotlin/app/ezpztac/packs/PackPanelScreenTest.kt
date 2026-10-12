package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Pack panel's screen: its rows, menus and history, and the rename dialog's content. `-Pezpz.screenshots` draws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class PackPanelScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val colin = PersonUi(1, "Colin McFadden", "CM", me = true, here = true)
    private val sam = PersonUi(2, "Sam Bell", "SB", me = false, here = true)
    private val jess = PersonUi(3, "Jess Reed", "JR", me = false)

    private fun item(uuid: String, kind: String, name: String, meta: String, who: PersonUi? = sam, isNew: Boolean = false, open: Boolean = false, fromLibrary: Boolean = false, original: String? = null, editing: List<String> = emptyList()) =
        PackItemUi(uuid, kind, name, isNew, who, meta, fromLibrary, original, editing, open, sending = false, canOpen = kind != "pointset", description = "$name. Show on the map")

    private val items = PackPanelUi(
        uuid = "p", name = "OP DK", loaded = true, subtitle = "Mission Pack · you are the owner", canEdit = true,
        description = "North Georgia night LZ/PZ and route planning for B Co 2-10 AVN.", members = 4, faces = listOf(colin, sam, jess), here = 1, newCount = 1,
        groups = listOf(
            ItemGroupUi(
                "lz", "LZ/PZ",
                listOf(
                    item("lz-1", "lz", "LZ IBIS (OWEST)", "Sam B. · 4 min ago", isNew = true, open = true, editing = listOf("Sam B.")),
                    item("lz-2", "lz", "LZ HAWK (ROCK)", "Jess R. · 13:21", who = jess, fromLibrary = true, original = "changed"),
                ),
            ),
            ItemGroupUi("route", "Routes", listOf(item("rt-1", "route", "OP DK INGRESS", "2 routes · Sam B. · 13:38"))),
            ItemGroupUi("pointset", "Point sets", listOf(item("ps-1", "pointset", "NORTH GA POINTS", "1,234 points · Colin M. · 09:40", who = colin))),
        ),
    )

    private val history = items.copy(
        tab = PackTab.HISTORY,
        history = HistoryUi(
            groups = listOf(
                EventGroupUi(
                    "New since you looked · 2", true,
                    listOf(
                        EventUi(13, sam, "Sam Bell", " moved Chalk 2 on LZ IBIS (OWEST).", false, "13:42 · LZ IBIS (OWEST)", true),
                        EventUi(12, jess, "Jess Reed", " changed the landing heading to 270° on LZ HAWK (ROCK).", false, "13:21 · LZ HAWK (ROCK)", true),
                    ),
                ),
                EventGroupUi(
                    "Earlier today", false,
                    listOf(
                        EventUi(10, jess, "Edit skipped:", " what it changed had already been removed. Jess R.’s change was not applied.", true, "10:48 · LZ IBIS (OWEST)", false),
                        EventUi(9, null, "", "The pack was finished.", false, "10:30 · Pack", false),
                    ),
                ),
            ),
            people = listOf(FilterChoice("Everyone", null), FilterChoice("You", "1"), FilterChoice("Sam Bell", "2")),
        ),
    )

    private fun show(state: PackPanelUi, mode: ThemeMode = ThemeMode.Dark, actions: PackPanelActions = PackPanelActions()) = compose.setContent {
        EzpzTheme(mode) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(16.dp)) {
                PackPanelContent(state, actions)
            }
        }
    }

    @Test
    fun `a row shows its item, and its menu closes it here, renames it or removes it for an editor`() {
        val log = mutableListOf<String>()
        show(items, actions = PackPanelActions(open = { log += "open $it" }, closeHere = { log += "close $it" }, tab = { log += "tab $it" }))
        compose.onNodeWithContentDescription("LZ HAWK (ROCK). Show on the map").performClick()
        compose.onNodeWithContentDescription("More actions for LZ IBIS (OWEST)").performClick()
        compose.onNodeWithText("Close here").performClick()
        compose.onNodeWithContentDescription("More actions for LZ HAWK (ROCK)").performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Remove from pack").performClick()
        compose.onNodeWithText("Remove “LZ HAWK (ROCK)” from OP DK?").assertIsDisplayed()
        compose.onNodeWithText(removeText("lz")).assertIsDisplayed()
        compose.onNodeWithContentDescription("History, new changes").performClick()
        assertEquals(listOf("open lz-2", "close lz-1", "tab HISTORY"), log)
    }

    @Test
    fun `a row's open menu stays with its item when the list changes under it`() {
        var state by mutableStateOf(items)
        val log = mutableListOf<String>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    PackPanelContent(state, PackPanelActions(open = { log += "open $it" }))
                }
            }
        }
        compose.onNodeWithContentDescription("More actions for LZ HAWK (ROCK)").performClick()
        compose.onNodeWithText("Open").assertIsDisplayed()
        val lz = state.groups[0]
        state = state.copy(groups = listOf(lz.copy(items = lz.items.drop(1))) + state.groups.drop(1))         // the row above it removed by someone else
        compose.waitForIdle()
        compose.onNodeWithText("Open").performClick()
        assertEquals(listOf("open lz-2"), log)
    }

    @Test
    fun `who else has an item open is said as such`() {
        show(items)
        compose.onNodeWithText("Sam B. has it open").assertIsDisplayed()
    }

    @Test
    fun `a viewer's menu has no rename or remove`() {
        show(items.copy(canEdit = false, readOnly = true))
        compose.onNodeWithContentDescription("More actions for LZ HAWK (ROCK)").performClick()
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertDoesNotExist()
        compose.onNodeWithText("Remove from pack").assertDoesNotExist()
        compose.onNodeWithText("View only").assertIsDisplayed()
    }

    @Test
    fun `the history filters by person`() {
        val picked = mutableListOf<String?>()
        show(history, actions = PackPanelActions(person = { picked += it }))
        compose.onNodeWithContentDescription("Filter by person: Everyone").performClick()
        compose.onNodeWithText("Sam Bell").performClick()
        assertEquals(listOf<String?>("2"), picked)
        compose.onNodeWithText("New since you looked · 2").assertIsDisplayed()
    }

    @Test
    fun `a rename that will not do is said at the field`() {
        val names = mutableListOf<String>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                PackRenameContent("lz-2", "LZ HAWK", onRename = { name, refused -> names += name; refused(PackPanelViewModel.NAME_REFUSED) }, onDismiss = {})
            }
        }
        compose.onNode(hasText("LZ HAWK") and hasText("Name")).performTextReplacement(" ")
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText(PackPanelViewModel.NAME_REFUSED).assertIsDisplayed()
        assertEquals(listOf(" "), names)
    }

    @Test
    fun `a pack still opening says so`() {
        show(PackPanelUi(uuid = "p", subtitle = "Opening…"))
        compose.onNodeWithText("Opening the pack…").assertIsDisplayed()
    }

    @Test fun packItems() = shot("pack-items", items)

    @Test fun packHistory() = shot("pack-history", history)

    @Test fun packItemsNight() = shot("pack-items-night", items, ThemeMode.Night)

    @Test fun packHistoryLight() = shot("pack-history-light", history, ThemeMode.Light)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
    fun packItemsNarrow() = shot("pack-items-narrow", items)

    private fun shot(name: String, state: PackPanelUi, mode: ThemeMode = ThemeMode.Dark) {
        show(state, mode)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }
}
