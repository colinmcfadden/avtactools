package app.ezpztac.packs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.network.Invite
import app.ezpztac.network.Team
import app.ezpztac.network.TeamMember
import app.ezpztac.network.TeamSummary
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

/** Teams' screen (no native design: the web's dialog, a page on a phone and two panes on a tablet), its name dialog and questions, and the switcher's way to it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class TeamsTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = TeamsActions(
        close = { log += "close" }, retryList = { log += "retry list" }, retryTeam = { log += "retry team" }, choose = { log += "choose $it" }, back = { log += "back" },
        newTeam = { log += "new" }, rename = { log += "rename" }, name = { log += "name $it" }, submitName = { log += "submit" }, dismissName = { log += "dismiss name" },
        email = { log += "email $it" }, inviteRole = { log += "as $it" }, invite = { log += "invite" }, revoke = { log += "revoke ${it.id}" },
        role = { m, r -> log += "role ${m.userId} $r" }, remove = { log += "remove ${it.userId}" }, leave = { log += "leave" }, delete = { log += "delete" },
        confirm = { log += "confirm" }, dismissConfirm = { log += "dismiss" },
    )

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-12T14:00:00Z")

    private fun member(id: Int, name: String, role: String) = TeamMember(id, name, "${name.lowercase().replace(' ', '.')}@army.mil", role, "2026-09-01T10:00:00")

    private val colin = member(6, "Colin McFadden", "owner")
    private val sam = member(7, "Sam Bell", "admin")
    private val jess = member(8, "Jess Reyes", "member")
    private val lopez = Invite(5, "sgt.lopez@army.mil", "member", "pending", null, "2026-10-18T10:00:00", "2026-10-04T10:00:00")
    private val teams = listOf(TeamSummary(3, "B Co 2-10 AVN", "owner", 3, "x"), TeamSummary(4, "HHC", "member", 12, "x"))
    private fun page(role: String = "owner", invites: List<Invite>? = listOf(lopez)) =
        TeamPage(Team(3, "B Co 2-10 AVN", role, 3, "2026-09-01T10:00:00", listOf(colin, sam, jess)), if (role == "member") emptyList() else invites)
    private val list = TeamsUi(open = true, me = 6, teams = teams, loaded = true, chosen = 3, page = page())
    private val owner = list.copy(detail = true)

    private fun show(state: TeamsUi, tablet: Boolean = false, mode: ThemeMode = ThemeMode.Dark) = compose.setContent {
        EzpzTheme(mode) {
            if (tablet) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TeamsContent(state, actions, tablet = true, Modifier.teamsDialog(), utc, now) }
            } else {
                TeamsContent(state, actions, tablet = false, Modifier.fillMaxSize(), utc, now)
            }
        }
    }

    @Test
    fun `a phone lists the person's teams, each opening its page, and New team`() {
        show(list)
        compose.onNodeWithText(TeamsWords.INTRO).assertExists()
        compose.onNodeWithText("3 members · Owner").assertExists()
        compose.onNode(hasText("HHC") and hasClickAction()).performClick()
        compose.onNode(hasText("New team") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Close teams").performClick()
        assertEquals(listOf("choose 4", "new", "close"), log)
        compose.onRoot().captureRoboImage("build/screenshots/teams-list.png")
    }

    @Test
    fun `someone in no team is told what a team is for, with New team`() {
        show(TeamsUi(open = true, me = 6, loaded = true))
        compose.onNodeWithText(TeamsWords.EMPTY).assertExists()
        compose.onNode(hasText("New team") and hasClickAction()).performClick()
        assertEquals(listOf("new"), log)
    }

    @Test
    fun `teams that could not be had are said with Try again, and the first load says it is loading`() {
        var state by mutableStateOf(TeamsUi(open = true, me = 6, listError = TeamsViewModel.TEAMS_FAILED))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { TeamsContent(state, actions, tablet = false, Modifier.fillMaxSize(), utc, now) } }
        compose.onNode(hasText("Try again") and hasClickAction()).performClick()
        assertEquals(listOf("retry list"), log)
        compose.onNodeWithText("Loading your teams…").assertDoesNotExist()
        state = TeamsUi(open = true, me = 6)
        compose.waitForIdle()
        compose.onNodeWithText("Loading your teams…").assertExists()
    }

    @Test
    fun `while a team's page is on its way its name is the list's, and it says it is loading`() {
        show(list.copy(detail = true, chosen = 4))                                                    // the page is still team 3's
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "HHC")).assertExists()
        compose.onNodeWithText("Loading the team…").assertExists()
        compose.onNodeWithText("Members · 3").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rename B Co 2-10 AVN").assertDoesNotExist()
    }

    @Test
    fun `Back on a phone goes from a team's page to the list before it closes, and a tablet's closes`() {
        teamsDismiss(tablet = false, detail = true, actions)()
        teamsDismiss(tablet = false, detail = false, actions)()
        teamsDismiss(tablet = true, detail = true, actions)()
        assertEquals(listOf("back", "close", "close"), log)
    }

    @Test
    fun `the list and each team's page keep a scroll of their own`() {
        val many = (1..20).map { TeamSummary(100 + it, "TEAM $it", "member", 2, "x") }
        val crowd = (1..12).map { member(20 + it, "Person $it", "member") }
        var state by mutableStateOf(list.copy(teams = teams + many, page = TeamPage(Team(3, "B Co 2-10 AVN", "owner", 13, "2026-09-01T10:00:00", listOf(colin) + crowd), emptyList())))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { TeamsContent(state, actions, tablet = false, Modifier.fillMaxSize(), utc, now) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("TEAM 20"))
        state = state.copy(detail = true)
        compose.waitForIdle()
        compose.onNodeWithText("13 members · you are the owner · since Sep 1").assertIsDisplayed()  // a team's page starts at its top
        state = state.copy(detail = false)
        compose.waitForIdle()
        compose.onNode(hasText("TEAM 20") and hasClickAction()).assertIsDisplayed()                  // the list is where it was
    }

    @Test
    fun `only a tablet marks the team shown as selected`() {
        show(list)
        compose.onNode(hasText("B Co 2-10 AVN") and hasClickAction()).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet marks the team shown as selected`() {
        show(list, tablet = true)
        compose.onNode(hasText("B Co 2-10 AVN") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNode(hasText("HHC") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun `the owner's page has inviting, roles, taking off, the invitations waiting and Delete, and Back goes to the list`() {
        show(owner)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "B Co 2-10 AVN")).assertExists()
        compose.onNodeWithText("3 members · you are the owner · since Sep 1").assertExists()
        compose.onNodeWithContentDescription("Rename B Co 2-10 AVN").performClick()
        compose.onNodeWithText(TeamsWords.INVITE_HINT).assertExists()
        compose.onNodeWithContentDescription("Invite as Member. Change role").performClick()
        compose.onNodeWithText("Admin").performClick()
        compose.onNode(hasText("Invite") and hasClickAction()).performClick()
        compose.onNodeWithText("Colin McFadden (you)").assertExists()
        compose.onNodeWithContentDescription("Member, role for Jess Reyes. Change role").performClick()
        compose.onNode(hasText("Owner") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Remove Sam Bell from B Co 2-10 AVN").performClick()
        compose.onNodeWithContentDescription("Remove Colin McFadden from B Co 2-10 AVN").assertDoesNotExist()
        compose.onNodeWithText("Invited Oct 4 as member · expires in 6 days").assertExists()
        compose.onNodeWithContentDescription("Revoke the invitation to sgt.lopez@army.mil").performClick()
        compose.onNode(hasText("Delete team") and hasClickAction()).performClick()
        compose.onNodeWithText("Leave team").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to your teams").performClick()
        assertEquals(listOf("rename", "as admin", "invite", "role 8 owner", "remove 7", "revoke 5", "delete", "back"), log)
        compose.onRoot().captureRoboImage("build/screenshots/teams-owner.png")
    }

    @Test
    fun `a member's page lists the team, roles as words, and Leave, with no inviting`() {
        show(owner.copy(me = 8, page = page(role = "member")))
        compose.onNodeWithText("Invite by email").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rename B Co 2-10 AVN").assertDoesNotExist()
        compose.onNodeWithText("Pending", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove Sam Bell from B Co 2-10 AVN").assertDoesNotExist()
        compose.onNodeWithText("Admin").assertExists()
        compose.onNode(hasText("Leave team") and hasClickAction()).performClick()
        compose.onNodeWithText("Delete team").assertDoesNotExist()
        assertEquals(listOf("leave"), log)
    }

    @Test
    fun `an admin invites members only, and takes off members but not admins`() {
        show(owner.copy(me = 7, page = page(role = "admin")))
        compose.onNodeWithContentDescription("Invite as Member. Change role").assertDoesNotExist()
        compose.onNodeWithText("Invite by email").assertExists()
        compose.onNodeWithContentDescription("Remove Jess Reyes from B Co 2-10 AVN").assertExists()
        compose.onNodeWithContentDescription("Remove Colin McFadden from B Co 2-10 AVN").assertDoesNotExist()
        compose.onNodeWithContentDescription("Member, role for Jess Reyes. Change role").assertDoesNotExist()   // roles are the owner's
        compose.onNodeWithContentDescription("Invites as Member").assertExists()                    // read whole, not "As" then "Member"
    }

    @Test
    fun `a refused address is said under the field, the keyboard's Send invites, and while a change is made nothing else can be`() {
        show(owner.copy(email = "nope", emailError = "Type their whole email address."))
        compose.onNodeWithText("Type their whole email address.").assertExists()
        compose.onNode(hasText("nope") and SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).performImeAction()
        assertEquals(listOf("invite"), log)
    }

    @Test
    fun `nothing changes while a change is made`() {
        show(owner.copy(busy = true))
        compose.onNodeWithContentDescription("Member, role for Jess Reyes. Change role").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove Sam Bell from B Co 2-10 AVN").assertIsNotEnabled()
        compose.onNode(hasText("Delete team") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `invitations that could not be had are said with Try again, and a team that could not be had too`() {
        show(owner.copy(page = page(invites = null)))
        compose.onNodeWithText("The invitations waiting could not be loaded.").assertExists()
        compose.onNode(hasText("Try again") and hasClickAction()).performClick()
        assertEquals(listOf("retry team"), log)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet has the list beside the team shown, and Done at its foot`() {
        show(list, tablet = true)
        val teamRow = compose.onNode(hasText("HHC") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val members = compose.onNodeWithText("Members · 3").fetchSemanticsNode().boundsInRoot
        assertTrue("$teamRow $members", teamRow.right <= members.left)
        compose.onNode(hasText("B Co 2-10 AVN") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Done") and hasClickAction()).performClick()
        assertEquals(listOf("close"), log)
        compose.onRoot().captureRoboImage("build/screenshots/teams-tablet.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `on a small phone at the largest text no name, address or button is cut`() {
        show(owner)
        for (words in listOf("Colin McFadden (you)", "colin.mcfadden@army.mil · joined Sep 1", "sgt.lopez@army.mil", "Revoke", "Delete team")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
        }
        compose.onRoot().captureRoboImage("build/screenshots/teams-narrow.png")
    }

    @Test
    fun `a team's name is asked for in the web's words, a new team's and a rename's`() {
        var naming by mutableStateOf(TeamNaming(rename = false, name = "A Co"))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { TeamNameContent(naming, "B Co 2-10 AVN", actions) } }
        compose.onNodeWithText("New team").assertExists()
        compose.onNodeWithText("You will be its owner. Invite people once it is made.").assertExists()
        compose.onNodeWithText("A unit or section people will recognise, such as B Co 2-10 AVN.").assertExists()
        compose.onNode(hasText("Create team") and hasClickAction()).performClick()
        naming = TeamNaming(rename = true, name = "B Co 2-10 AVN", error = "Give it a name.")
        compose.waitForIdle()
        compose.onNodeWithText("Rename B Co 2-10 AVN").assertExists()
        compose.onNodeWithText("Give it a name.").assertExists()
        compose.onNode(hasText("Rename") and hasClickAction()).performClick()
        naming = naming.copy(sending = true, error = null)
        compose.waitForIdle()
        compose.onNode(hasText("Renaming…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Cancel") and hasClickAction()).assertIsNotEnabled()
        assertEquals(listOf("submit", "submit"), log)
    }

    @Test
    fun `each change that cannot be taken back is asked first, in the web's words`() {
        var state by mutableStateOf(owner.copy(confirm = TeamsConfirm.Remove(3, sam)))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { TeamsQuestion(state, actions) } }
        compose.onNodeWithText("Remove Sam Bell from B Co 2-10 AVN?").assertExists()
        compose.onNodeWithText("They stay in any pack they were added to by name; packs shared with the team stop opening for them.").assertExists()
        compose.onNodeWithText("Remove").performClick()
        state = owner.copy(confirm = TeamsConfirm.HandOver(3, jess))
        compose.waitForIdle()
        compose.onNodeWithText("Make Jess Reyes the owner of B Co 2-10 AVN?").assertExists()
        compose.onNodeWithText(TEAM_HAND_OVER_TEXT).assertExists()
        state = owner.copy(confirm = TeamsConfirm.Delete(3))
        compose.waitForIdle()
        compose.onNodeWithText("It goes for all 3 members. Packs shared with it are not deleted, but stop opening for anyone who was only in them through the team.").assertExists()
        state = owner.copy(confirm = TeamsConfirm.Leave(3))
        compose.waitForIdle()
        compose.onNodeWithText("Leave B Co 2-10 AVN?").assertExists()
        state = owner.copy(confirm = TeamsConfirm.Revoke(3, lopez))
        compose.waitForIdle()
        compose.onNodeWithText("Withdraw the invitation to sgt.lopez@army.mil?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("confirm", "dismiss"), log)
    }

    @Test
    fun lightShot() {
        show(owner, mode = ThemeMode.Light)
        compose.onRoot().captureRoboImage("build/screenshots/teams-light.png")
    }

    // -- The switcher's way to it -------------------------------------------------------------

    private val switcher = SwitcherUi(current = null, library = "Personal", packs = emptyList(), teams = "B Co 2-10 AVN and 1 other team", loaded = true)

    @Test
    fun `the switcher's teams line has Manage teams when it is given, and not otherwise`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column {
                    SwitcherContent(switcher, SwitcherActions(teams = { log += "teams" }))
                    SwitcherContent(switcher.copy(teams = "No teams"), SwitcherActions())
                }
            }
        }
        compose.onNode(hasText("Manage teams") and hasClickAction()).performClick()
        assertEquals(listOf("teams"), log)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `on a small phone at the largest text Manage teams goes under the teams line, neither cut`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { SwitcherContent(switcher, SwitcherActions(teams = { log += "teams" }), Modifier.verticalScroll(rememberScrollState())) }   // as its sheet scrolls it
        }
        for (words in listOf("Manage teams", switcher.teams)) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
        }
        val line = compose.onNodeWithText(switcher.teams, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNode(hasText("Manage teams") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("$line $button", button.top >= line.bottom)
    }
}
