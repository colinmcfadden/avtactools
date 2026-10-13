package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.network.UserBrief
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

/** New Mission Pack's screen (the design's NewPack, phone and tablet) and the switcher's way to it. `-Pezpz.screenshots` draws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class NewPackTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = NewPackActions(
        name = { log += "name $it" }, audience = { log += "audience $it" }, team = { log += "team $it" }, teamRole = { log += "can $it" },
        query = { log += "query $it" }, pick = { log += "pick ${it.id}" }, pickEmail = { log += "invite" }, role = { key, role -> log += "role $key $role" },
        remove = { log += "remove $it" }, create = { log += "create" }, close = { log += "close" },
    )
    private val teams = listOf(TeamOption(3, "B Co 2-10 AVN", 18), TeamOption(4, "HHC", 1))
    private val team = NewPackUi(open = true, name = "OP DK", description = "North Georgia night LZ/PZ and route planning.", audience = Audience.TEAM, teams = teams, teamsLoaded = true, teamId = 3)

    private fun show(state: NewPackUi, tablet: Boolean = false, mode: ThemeMode = ThemeMode.Dark) = compose.setContent {
        EzpzTheme(mode) {
            if (tablet) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), contentAlignment = Alignment.Center) {
                    NewPackContent(state, actions, tablet = true, modifier = Modifier.widthIn(max = NEW_PACK_WIDTH))
                }
            } else {
                NewPackContent(state, actions, tablet = false, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test
    fun `a team's pack, the team and what it can do are named for a screen reader, and Create makes it`() {
        show(team)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "New Mission Pack")).assertExists()
        compose.onNodeWithText("A team").assertIsSelected()
        compose.onNodeWithContentDescription("Team, B Co 2-10 AVN, 18 members. Change team").performClick()
        compose.onNodeWithText("HHC · 1 member").performClick()
        compose.onNodeWithContentDescription("They can, Edit. Change what the team can do").performClick()
        compose.onNodeWithText("View").performClick()
        compose.onNodeWithText("Also invite by name or email").assertExists()
        compose.onNodeWithText(PEOPLE_HINT).assertExists()
        compose.onNode(hasText("Create") and hasClickAction()).performClick()
        assertEquals(listOf("team 4", "can viewer", "create"), log)
        compose.onNodeWithText("Only you can open it", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel, close without creating a pack").performClick()
        assertEquals("close", log.last())
    }

    @Test
    fun `someone in no team cannot choose one, and is told why`() {
        show(NewPackUi(open = true, teamsLoaded = true))
        compose.onNodeWithText("A team").assertIsNotEnabled()
        compose.onNodeWithText(NewPackUi.NO_TEAM).assertExists()
        compose.onNodeWithText("Only you can open it. You can invite people later from Members.").assertExists()
        compose.onNodeWithText("Invite by name or email").assertDoesNotExist()                     // just them: nobody to invite
        compose.onNodeWithText(PEOPLE_HINT).assertDoesNotExist()
        compose.onNodeWithText("Specific people").performClick()
        assertEquals(listOf("audience PEOPLE"), log)
    }

    @Test
    fun `what the field found is offered, a teammate by name and an address to invite, and those chosen can be a viewer or taken off`() {
        show(
            NewPackUi(
                open = true, audience = Audience.PEOPLE, query = "sam", teamsLoaded = true, teams = teams, teamId = 3,
                found = listOf(UserBrief(11, "Sam Bell", "sam.bell@army.mil")), typedEmail = null,
                invitees = listOf(Invitee(12, "Jess Reed", "jess.reed@army.mil"), Invitee(null, "", "marcus.webb@army.mil", role = "viewer")),
            ),
        )
        compose.onNodeWithText("Invite by name or email").assertExists()
        compose.onNodeWithContentDescription("Team, B Co 2-10 AVN, 18 members. Change team").assertDoesNotExist()   // the team is not who it is for
        compose.onNodeWithText("Only you can open it", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sam Bell").performClick()
        compose.onNodeWithContentDescription("Editor: make Jess Reed a viewer").performClick()
        compose.onNodeWithContentDescription("Viewer: make marcus.webb@army.mil an editor").performClick()
        compose.onNodeWithContentDescription("Remove marcus.webb@army.mil").performClick()
        assertEquals(listOf("pick 11", "role user:12 viewer", "role email:marcus.webb@army.mil editor", "remove email:marcus.webb@army.mil"), log)
        compose.onRoot().captureRoboImage("build/screenshots/new-pack-people.png")
    }

    @Test
    fun `an address typed is offered as an invitation that sends an email`() {
        show(NewPackUi(open = true, audience = Audience.PEOPLE, query = "marcus.webb@army.mil", typedEmail = "marcus.webb@army.mil", teamsLoaded = true))
        compose.onNodeWithText("They get an email with a link to join").assertExists()
        compose.onNodeWithText("Invite marcus.webb@army.mil").performClick()
        assertEquals(listOf("invite"), log)
    }

    @Test
    fun `while it is made nothing can be put away or made again, and why it was not made is said at the name`() {
        show(team.copy(busy = true))
        compose.onNode(hasText("Creating…") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cancel, close without creating a pack").assertIsNotEnabled()
        compose.onNodeWithText("A team").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Team, B Co 2-10 AVN, 18 members. Change team").assertIsNotEnabled()
    }

    @Test
    fun `why the name will not do is said under it`() {
        show(team.copy(name = "", nameError = "Give the pack a name."))
        compose.onNodeWithText("Give the pack a name.").assertIsDisplayed()
        compose.onNode(hasText("Create") and hasClickAction()).assertIsEnabled()                     // the web's way: refused in words, never a grey button
    }

    @Test
    fun `why the pack was not made is said under the bar, in sight however far the form is scrolled`() {
        val words = "There is no connection to the server. Try again when you are back online."
        show(team.copy(error = words, invitees = (1..8).map { Invitee(it, "Person $it", "p$it@army.mil") }))
        compose.onNodeWithText(INFO).performScrollTo()                                               // the foot of a long form
        compose.onNodeWithText(words).assertIsDisplayed()
        compose.onNode(hasText(words) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))).assertExists()
    }

    @Test
    fun `a name typed and not chosen is said under the person field, which takes the keyboard`() {
        show(NewPackUi(open = true, audience = Audience.PEOPLE, query = "sam", teamsLoaded = true, peopleError = NewPackViewModel.PICK_SOMEONE))
        compose.onNodeWithText(NewPackViewModel.PICK_SOMEONE).assertExists()
        compose.onNodeWithText(PEOPLE_HINT).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("sam")).assertIsFocused()
    }

    @Test
    fun `a name refused takes the keyboard to its field`() {
        show(team.copy(name = "", nameError = "Give the pack a name."))
        compose.onNode(hasSetTextAction() and hasText("Name")).assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w412dp-h560dp-xxhdpi")
    fun `what the field found is brought into sight below it`() {
        show(team.copy(query = "sam", found = listOf(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))))
        compose.onNode(hasText("Sam Bell") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `what the field found is said with how many, and a face is never read aloud`() {
        assertEquals("1 person on your teams", foundWords(1))
        assertEquals("2 people on your teams", foundWords(2))
        show(
            NewPackUi(
                open = true, audience = Audience.PEOPLE, query = "s", teamsLoaded = true,
                found = listOf(UserBrief(11, "Sam Bell", "sam.bell@army.mil"), UserBrief(13, "Sid Ray", "sid.ray@army.mil")),
                invitees = listOf(Invitee(12, "Jess Reed", "jess.reed@army.mil")),
            ),
        )
        compose.onNode(hasText("2 people on your teams") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        compose.onNodeWithText("JR").assertDoesNotExist()                                           // the name beside it says who
        compose.onNodeWithText("SB", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Sam Bell") and hasClickAction()).assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the tablet adds an address typed with its own button, and its dialog is the design's 560 dp`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NewPackContent(
                        NewPackUi(open = true, audience = Audience.PEOPLE, query = "a@b.co", typedEmail = "a@b.co", teamsLoaded = true), actions, tablet = true,
                        modifier = Modifier.tabletDialog(),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Add to the invite list").performClick()
        assertEquals(listOf("invite"), log)
        val dialog = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "New Mission Pack")).fetchSemanticsNode()
        assertEquals(560f, with(compose.density) { dialog.size.width.toDp().value }, 1f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the tablet add button waits for a whole address`() {
        show(NewPackUi(open = true, audience = Audience.PEOPLE, query = "sam", teamsLoaded = true), tablet = true)
        compose.onNodeWithContentDescription("Add to the invite list").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun `a long address is never cut, its buttons going under it, and a short name keeps them beside it`() {
        val address = "jonathan.r.richardson.mil@army.mil"
        show(NewPackUi(open = true, audience = Audience.PEOPLE, teamsLoaded = true, invitees = listOf(Invitee(null, "", address), Invitee(12, "Jess Reed", "jess.reed@army.mil"))))
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(address, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertFalse("the address is cut", layout.single().didOverflowHeight || layout.single().isLineEllipsized(layout.single().lineCount - 1))
        val words = compose.onNodeWithText(address, useUnmergedTree = true).fetchSemanticsNode()
        val remove = compose.onNodeWithContentDescription("Remove $address").fetchSemanticsNode()
        assertTrue(words.positionInRoot.y + words.size.height <= remove.positionInRoot.y)
        assertEquals(1, layout.single().lineCount)                                                    // under its buttons it has the row's width
        compose.onNodeWithContentDescription("Remove $address").assertIsDisplayed()
        val name = compose.onNodeWithText("Jess Reed", useUnmergedTree = true).fetchSemanticsNode()
        val off = compose.onNodeWithContentDescription("Remove Jess Reed").fetchSemanticsNode()
        assertTrue(off.positionInRoot.y < name.positionInRoot.y + name.size.height)                  // beside it
        compose.onRoot().captureRoboImage("build/screenshots/new-pack-invitees-narrow.png")
    }

    @Test
    fun `teams that could not be had are said, and nobody is told they are in none`() {
        show(NewPackUi(open = true, teamsError = true))
        compose.onNodeWithText(NewPackUi.TEAMS_FAILED).assertExists()
        compose.onNodeWithText(NewPackUi.NO_TEAM).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the tablet's dialog has its title and close, and Cancel and Create pack at its foot, the Just me line under the choice`() {
        show(NewPackUi(open = true, name = "OP DK", teamsLoaded = true), tablet = true)
        compose.onNodeWithContentDescription("Close").assertExists()
        compose.onNode(hasText("Create pack") and hasClickAction()).performClick()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(listOf("create", "close"), log)
        val choice = compose.onNodeWithText("Just me").fetchSemanticsNode().boundsInRoot
        val lock = compose.onNodeWithText("Only you can open it", substring = true).fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithText(INFO).fetchSemanticsNode().boundsInRoot
        assertTrue(choice.bottom <= lock.top && lock.bottom <= info.top)
        compose.onRoot().captureRoboImage("build/screenshots/new-pack-tablet.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `on a small phone at the largest text no choice or field is cut`() {
        show(team.copy(invitees = listOf(Invitee(12, "Jess Reed", "jess.reed@army.mil"))))
        for (words in listOf("Just me", "A team", "Specific people", "B Co 2-10 AVN", "18 members", "Edit")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
        }
        // The team and what it can do go one under the other (measured where they are laid out, below the window at this size).
        val teamField = compose.onNodeWithContentDescription("Team, B Co 2-10 AVN, 18 members. Change team").fetchSemanticsNode()
        val roleField = compose.onNodeWithContentDescription("They can, Edit. Change what the team can do").fetchSemanticsNode()
        assertTrue(teamField.positionInRoot.y + teamField.size.height <= roleField.positionInRoot.y)
        compose.onRoot().captureRoboImage("build/screenshots/new-pack-narrow.png")
    }

    @Test
    fun phoneShot() {
        show(team)
        compose.onRoot().captureRoboImage("build/screenshots/new-pack-phone.png")
    }

    @Test
    fun lightShot() {
        show(NewPackUi(open = true, teamsLoaded = true), mode = ThemeMode.Light)
        compose.onRoot().captureRoboImage("build/screenshots/new-pack-light.png")
    }

    // -- The switcher's way to it ----------------------------------------------------------------

    private val switcher = SwitcherUi(current = null, library = "Personal · 1 LZ/PZ, 0 route sets, 0 point sets", packs = emptyList(), teams = "B Co 2-10 AVN", loaded = true)

    @Test
    fun `the switcher offers New pack beside its packs when it can make one, and closes as it opens it`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column {
                    SwitcherContent(switcher, SwitcherActions(close = { log += "close" }, newPack = { log += "new" }))
                }
            }
        }
        compose.onNode(hasText("New pack") and hasClickAction()).performClick()
        assertEquals(listOf("new"), log)
    }

    @Test
    fun `without a way to make one the switcher offers none`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Column { SwitcherContent(switcher, SwitcherActions()) } } }
        compose.onNodeWithText("New pack").assertDoesNotExist()
    }
}
