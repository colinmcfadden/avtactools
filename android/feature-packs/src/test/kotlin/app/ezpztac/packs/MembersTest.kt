package app.ezpztac.packs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.network.Invite
import app.ezpztac.network.PackMember
import app.ezpztac.network.UserBrief
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

/** Members' screen (the design's Members, phone and tablet), the questions asked before a change, and the Pack panel's way to it. `-Pezpz.screenshots` draws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class MembersTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = MembersActions(
        close = { log += "close" }, retry = { log += "retry" }, query = { log += "query $it" }, pick = { log += "pick ${it.id}" }, inviteRole = { log += "as $it" },
        invite = { log += "invite" }, role = { m, r -> log += "role ${m.userId} $r" }, remove = { log += "remove ${it.userId}" }, resend = { log += "resend ${it.id}" },
        revoke = { log += "revoke ${it.id}" }, shareTo = { log += "share to $it" }, shareRole = { log += "share as $it" }, share = { log += "share" },
        sharedRole = { log += "shared as $it" }, stopSharing = { log += "stop sharing" }, confirm = { log += "confirm" }, dismissConfirm = { log += "dismiss" },
    )

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-12T14:00:00Z")

    private fun member(id: Int, name: String, role: String = "editor", seen: String? = "2026-10-12T12:00:00") =
        PackMember(id, name, "${name.lowercase().replace(' ', '.')}@army.mil", role, "2026-10-01T10:00:00", seen)

    private val colin = member(6, "Colin McFadden", "owner")
    private val sam = member(7, "Sam Bell")
    private val jess = member(8, "Jess Reyes")
    private val marcus = member(9, "Marcus Webb", "viewer")
    private val lopez = Invite(5, "sgt.lopez@army.mil", "editor", "pending", null, "2026-10-18T10:00:00", "2026-10-04T10:00:00")
    private val teams = listOf(TeamOption(3, "B Co 2-10 AVN", 18), TeamOption(4, "HHC", 1))
    private val owner = MembersUi(
        open = true, uuid = "p-dk", pack = "OP DK", me = 6, here = setOf(6, 7, 8), teams = teams,
        people = PackPeople("OP DK", "owner", listOf(colin, sam, jess, marcus), listOf(lopez), null),
    )

    private fun show(state: MembersUi, tablet: Boolean = false, mode: ThemeMode = ThemeMode.Dark) = compose.setContent {
        EzpzTheme(mode) {
            if (tablet) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MembersContent(state, actions, tablet = true, Modifier.membersDialog(), utc, now) }
            } else {
                MembersContent(state, actions, tablet = false, Modifier.fillMaxSize(), utc, now)
            }
        }
    }

    @Test
    fun `the owner sees everyone with where they are, and changes a role, takes someone off, or sends an invitation again or withdraws it`() {
        show(owner)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Members of OP DK")).assertExists()
        compose.onNodeWithText("Mission Pack · you are the owner").assertExists()
        compose.onNodeWithText("Colin McFadden (you)").assertExists()
        compose.onNodeWithText("Owner").assertExists()                                                // the owner's own role is fixed
        assertEquals(2, compose.onAllNodesWithText("Here now").fetchSemanticsNodes().size)            // Sam and Jess; never this person, and the dot is drawn
        compose.onNodeWithText("●", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Seen 2 h ago").assertExists()
        compose.onNodeWithText("Members · 4").assertExists()
        compose.onNodeWithContentDescription("Editor, role for Sam Bell. Change role").performClick()
        compose.onNodeWithText("Viewer").performClick()
        compose.onNodeWithContentDescription("Remove Jess Reyes from OP DK").performClick()
        compose.onNodeWithText("Pending · 1").assertExists()
        compose.onNodeWithText("Invited Oct 4 as Editor · expires in 6 days").assertExists()
        compose.onNodeWithContentDescription("Resend the invitation to sgt.lopez@army.mil").performClick()
        compose.onNodeWithContentDescription("Revoke the invitation to sgt.lopez@army.mil").performClick()
        compose.onNodeWithContentDescription("Back to OP DK").performClick()
        assertEquals(listOf("role 7 viewer", "remove 8", "resend 5", "revoke 5", "close"), log)
        compose.onRoot().captureRoboImage("build/screenshots/members-phone.png")
    }

    @Test
    fun `the owner invites by name or email, as the role chosen beside the field`() {
        show(owner.copy(query = "Jamie", found = listOf(UserBrief(11, "Jamie Ortiz", "jamie.ortiz@army.mil"), UserBrief(12, "Jamie Cole", "jamie.cole@army.mil"))))
        compose.onNodeWithText("2 people on your teams").assertExists()
        compose.onNodeWithText(MembersWords.HINT).assertExists()
        compose.onNode(hasText("Invite") and hasClickAction()).assertIsNotEnabled()                    // nobody chosen yet
        compose.onNode(hasText("Jamie Ortiz") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Invite as Editor. Change role").performClick()
        compose.onNodeWithText("Viewer").performClick()
        compose.onNodeWithContentDescription("Clear the name").performClick()
        assertEquals(listOf("pick 11", "as viewer", "query "), log)
        compose.onRoot().captureRoboImage("build/screenshots/members-invite.png")
    }

    @Test
    fun `someone chosen can be invited, a refusal is said under the field, and Invite opened the page at the field`() {
        show(owner.copy(query = "Jamie Ortiz", chosen = Invitee(11, "Jamie Ortiz", "jamie.ortiz@army.mil"), inviteError = "They are already in it.", focusInvite = true))
        compose.onNodeWithText("They are already in it.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Jamie Ortiz")).assertIsFocused()
        compose.onNode(hasText("Invite") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals("invite", log.last())
    }

    @Test
    fun `the team it could be shared with is offered with what it can do, and one it is shared with can be changed or stopped`() {
        show(owner)
        compose.onNodeWithContentDescription("Team to share with, B Co 2-10 AVN. Change team").performClick()
        compose.onNodeWithText("HHC").performClick()
        compose.onNodeWithContentDescription("What the team can do, Can edit. Change").performClick()
        compose.onNodeWithText("Can view").performClick()
        compose.onNodeWithContentDescription("Share with team B Co 2-10 AVN, 18 members").performClick()
        assertEquals(listOf("share to 4", "share as viewer", "share"), log)
    }

    @Test
    fun `a pack shared with a team says so, and what the team can do is changed there`() {
        show(owner.copy(people = owner.people!!.copy(shared = SharedTeam(3, "B Co 2-10 AVN", "viewer")), teams = teams.take(1)))
        compose.onNodeWithText("Shared with this team").assertExists()
        compose.onNodeWithContentDescription("What B Co 2-10 AVN can do, Can view. Change").performClick()
        compose.onNodeWithText("Can edit").performClick()
        compose.onNode(hasText("Stop sharing") and hasClickAction()).performClick()
        assertEquals(listOf("shared as editor", "stop sharing"), log)
        compose.onNodeWithText("not shared with this pack", substring = true).assertDoesNotExist()
    }

    @Test
    fun `someone not the owner sees the list only, roles as words`() {
        show(owner.copy(me = 7, people = owner.people!!.copy(role = "editor")))                     // invitations there or not, they are the owner's
        compose.onNodeWithText("Invite").assertDoesNotExist()
        compose.onNodeWithText("Pending", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Share with team").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove Jess Reyes from OP DK").assertDoesNotExist()
        compose.onNodeWithText("Viewer").assertExists()
        compose.onNodeWithText("Sam Bell (you)").assertExists()
        compose.onNodeWithText("Mission Pack · you are an editor").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a tablet too, someone not the owner has the list alone`() {
        show(owner.copy(me = 7, people = owner.people!!.copy(role = "editor")), tablet = true)
        compose.onNodeWithText("Name or email").assertDoesNotExist()
        compose.onNodeWithText("Share with team").assertDoesNotExist()
        compose.onNode(hasText("Invite") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Pending", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Members · 4").assertIsDisplayed()
    }

    @Test
    fun `invitations that could not be had are said where they would be, with Try again, and the members stay`() {
        show(owner.copy(people = owner.people!!.copy(pending = null)))
        compose.onNodeWithText("The invitations waiting could not be loaded.").assertExists()
        compose.onNodeWithText("Members · 4").assertExists()
        compose.onNode(hasText("Try again") and hasClickAction()).performClick()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `the keyboard's Send takes the one teammate found, else invites who the field holds`() {
        show(owner.copy(query = "Jam", found = listOf(UserBrief(11, "Jamie Ortiz", "jamie.ortiz@army.mil"))))
        compose.onNode(hasSetTextAction() and hasText("Jam")).performImeAction()
        assertEquals(listOf("pick 11"), log)
    }

    @Test
    fun `with someone chosen the keyboard's Send invites them`() {
        show(owner.copy(query = "Jamie Ortiz", chosen = Invitee(11, "Jamie Ortiz", "jamie.ortiz@army.mil")))
        compose.onNode(hasSetTextAction() and hasText("Jamie Ortiz")).performImeAction()
        assertEquals(listOf("invite"), log)
    }

    @Test
    fun `people that could not be had are said with Try again, and the first load says it is loading`() {
        show(owner.copy(people = null, loadError = "There is no connection to the server. Try again when you are back online."))
        compose.onNode(hasText("Try again") and hasClickAction()).performClick()
        assertEquals(listOf("retry"), log)
        compose.onNodeWithText("Loading the members…").assertDoesNotExist()
    }

    @Test
    fun `while a change is made nothing else can be`() {
        show(owner.copy(busy = true))
        compose.onNodeWithContentDescription("Editor, role for Sam Bell. Change role").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove Sam Bell from OP DK").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Resend the invitation to sgt.lopez@army.mil").assertIsNotEnabled()
    }

    @Test
    fun `each change that cannot be taken back is asked first, in the web's words`() {
        var state by mutableStateOf(owner.copy(confirm = MembersConfirm.Remove(sam)))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { MembersQuestion(state, actions, utc, now) } }
        compose.onNodeWithText("Take Sam Bell off OP DK?").assertExists()
        compose.onNodeWithText("They can no longer open it. What they added stays in the pack. Last seen Oct 12 at 12:00.").assertExists()
        compose.onNodeWithText("Take off").performClick()
        state = owner.copy(confirm = MembersConfirm.HandOver(sam))
        compose.waitForIdle()
        compose.onNodeWithText("Make Sam Bell the owner of OP DK?").assertExists()
        compose.onNodeWithText(HAND_OVER_TEXT).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        state = owner.copy(confirm = MembersConfirm.Revoke(lopez))
        compose.waitForIdle()
        compose.onNodeWithText("Withdraw the invitation to sgt.lopez@army.mil?").assertExists()
        state = owner.copy(confirm = MembersConfirm.StopSharing(SharedTeam(3, "B Co 2-10 AVN", "editor")))
        compose.waitForIdle()
        compose.onNodeWithText("Stop sharing OP DK with B Co 2-10 AVN?").assertExists()
        assertEquals(listOf("confirm", "dismiss"), log)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the tablet's dialog has the invite and the team on the left, the people on the right, and the note with Done at its foot`() {
        show(owner, tablet = true)
        val invite = compose.onNodeWithText("Name or email").fetchSemanticsNode().boundsInRoot
        val people = compose.onNodeWithText("Members · 4").fetchSemanticsNode().boundsInRoot
        assert(invite.right <= people.left) { "$invite $people" }
        compose.onNodeWithText(MembersWords.NOTE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Invite as Viewer").performClick()
        compose.onNode(hasText("Done") and hasClickAction()).performClick()
        assertEquals(listOf("as viewer", "close"), log)
        compose.onRoot().captureRoboImage("build/screenshots/members-tablet.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `on a small phone at the largest text no name, address or button is cut`() {
        show(owner)
        for (words in listOf("Colin McFadden (you)", "colin.mcfadden@army.mil", "sgt.lopez@army.mil", "Invited Oct 4 as Editor · expires in 6 days", "Resend", "Revoke")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
        }
        compose.onRoot().captureRoboImage("build/screenshots/members-narrow.png")
    }

    @Test
    fun lightShot() {
        show(owner, mode = ThemeMode.Light)
        compose.onRoot().captureRoboImage("build/screenshots/members-light.png")
    }

    // -- The Pack panel's way in -------------------------------------------------------------------

    @Test
    fun `the Pack panel's members row opens Members, and its owner's Invite opens it at the invite field`() {
        val panel = PackPanelUi(uuid = "p", name = "OP DK", loaded = true, members = 4, here = 2, owner = true, faces = emptyList())
        compose.setContent { EzpzTheme(ThemeMode.Dark) { PackPanelContent(panel, PackPanelActions(members = { log += "members $it" })) } }
        compose.onNodeWithContentDescription("4 members, 2 here now. Manage members").performClick()
        compose.onNode(hasText("Invite") and hasClickAction()).performClick()
        assertEquals(listOf("members false", "members true"), log)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun `on a small phone the owner's Invite goes under the members row rather than squeeze its words`() {
        val faces = (1..5).map { PersonUi(it, "Person $it", "P$it", me = it == 1, here = it <= 3) }
        val panel = PackPanelUi(uuid = "p", name = "OP DK", loaded = true, members = 18, here = 3, owner = true, faces = faces)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) { PackPanelContent(panel, PackPanelActions()) }
            }
        }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("18 members · 3 here now", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val words = layout.single()
        for (line in 0 until words.lineCount) {
            val end = words.getLineEnd(line, visibleEnd = true)
            if (end in 1 until words.layoutInput.text.length) assertTrue("broken inside a word", words.layoutInput.text[end - 1] == ' ' || words.layoutInput.text[end] == ' ')
        }
        val row = compose.onNodeWithContentDescription("18 members, 3 here now. Manage members").fetchSemanticsNode()
        val invite = compose.onNode(hasText("Invite") and hasClickAction()).fetchSemanticsNode()
        assertTrue(row.positionInRoot.y + row.size.height <= invite.positionInRoot.y)
    }

    @Test
    fun `someone not the owner has the members row and no Invite`() {
        val panel = PackPanelUi(uuid = "p", name = "OP DK", loaded = true, members = 1, owner = false, faces = emptyList())
        compose.setContent { EzpzTheme(ThemeMode.Dark) { PackPanelContent(panel, PackPanelActions(members = { log += "members $it" })) } }
        compose.onNodeWithContentDescription("1 member. Manage members").performClick()
        compose.onNodeWithText("Invite").assertDoesNotExist()
        assertEquals(listOf("members false"), log)
    }
}
