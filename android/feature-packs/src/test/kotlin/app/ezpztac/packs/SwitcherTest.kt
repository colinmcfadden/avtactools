package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.Invite
import app.ezpztac.network.InvitePack
import app.ezpztac.network.InviteTeam
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackPerson
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The switcher's words, its screen and the pack's chip (the design's Switcher and PackLive). `-Pezpz.screenshots` draws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class SwitcherTest {
    @get:Rule
    val compose = createComposeRule()

    private fun summary(name: String, status: String = "active", role: String = "editor", audience: Int = 4, finishedAt: String? = null) = PackSummary(
        "u-$name", name, "", status, role, PackPerson(1, "Alex Park"), headSeq = 1, seenSeq = 1, memberCount = audience, audienceCount = audience, itemCount = 0,
        itemCounts = PackItemCounts(0, 0, 0), finishedAt = finishedAt, createdAt = "2026-09-01T10:00:00", updatedAt = "2026-09-01T10:00:00",
    )

    @Test
    fun `the words are the web's`() {
        assertEquals("3 LZ/PZ · 2 route sets · 1 point set", PackWords.counts(PackItemCounts(3, 2, 1)))
        assertEquals("", PackWords.counts(PackItemCounts(0, 0, 0)))
        assertEquals("Personal · 1 LZ/PZ, 0 route sets, 1 point set", PackWords.library(LibraryCounts(1, 0, 1)))
        assertEquals("4 members · you are Owner", PackWords.packMeta(summary("OP DK", role = "owner")))
        assertEquals("1 member · you are Viewer", PackWords.packMeta(summary("SOLO", role = "viewer", audience = 0)))
        assertEquals("Finished Sep 22 · read-only", PackWords.packMeta(summary("OLD", status = "finished", finishedAt = "2026-09-22T14:00:00"), utc, today))
        assertEquals("Finished · read-only", PackWords.packMeta(summary("OLD", status = "finished", finishedAt = null), utc, today))
        assertEquals("Sep 22", PackWords.shortDate("2026-09-22T14:00:00+00:00", utc, today))
        assertEquals(TEAMLESS, PackWords.teams(emptyList()))
        assertEquals("HHC", PackWords.teams(listOf(TeamSummary(1, "HHC", "member", 3, "x"))))
        assertEquals("Someone invited you to a pack as Viewer", PackWords.invite(Invite(1, null, "viewer", "pending", null, "x", "x")))
        assertEquals("Ann invited you to the team HHC", PackWords.invite(Invite(1, null, "member", "pending", PackPerson(null, "Ann"), "x", "x", team = InviteTeam(1, "HHC"))))
        assertEquals(
            InviteUi(3, "OP IBIS", "OP IBIS", team = false, role = "Editor"),                         // an inviter named as the pack: the parts say which is which
            PackWords.inviteUi(Invite(3, null, "editor", "pending", PackPerson(2, "OP IBIS"), "x", "x", pack = InvitePack("p-ib", "OP IBIS"))),
        )
        // The account button's rule: whole characters, letters and digits only.
        assertEquals("CM", PackWords.initials("Colin McFadden"))
        assertEquals("SB", PackWords.initials("Sam B."))
        assertEquals("J", PackWords.initials("jo"))
        assertEquals("AZ", PackWords.initials("\uD83D\uDE00 Ann Zed"))                         // an emoji is not a letter, and is never cut in half
        assertEquals("\uD835\uDC00", PackWords.initials("\uD835\uDC00lpha"))                   // a letter outside the BMP is kept whole
        assertEquals("?", PackWords.initials("\uD83D\uDE00"))
    }

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 12)

    @Test
    fun `a date is the person's, from a time with or without its offset, and names the year when it is not this one`() {
        val chicago = ZoneId.of("America/Chicago")
        assertEquals("Sep 21", PackWords.shortDate("2026-09-22T02:00:00", chicago, today))       // no offset: UTC, which is the evening before in Chicago
        assertEquals("Sep 22", PackWords.shortDate("2026-09-22T02:00:00-05:00", chicago, today))
        assertEquals("Sep 23", PackWords.shortDate("2026-09-22T20:00:00", ZoneId.of("Asia/Tokyo"), today))
        assertEquals("Dec 31, 2025", PackWords.shortDate("2026-01-01T03:00:00", chicago, today))   // the year it was there, not here
        assertEquals("Jan 1", PackWords.shortDate("2026-01-01T03:00:00", utc, today))
        assertEquals("Mar 4, 2027", PackWords.shortDate("2027-03-04T12:00:00Z", utc, today))
        assertEquals("", PackWords.shortDate("next tuesday", utc, today))
        assertEquals("", PackWords.shortDate(null, utc, today))
        assertEquals("Finished Dec 31, 2025 · read-only", PackWords.packMeta(summary("OLD", status = "finished", finishedAt = "2026-01-01T03:00:00"), chicago, today))
    }

    private val people = listOf(PersonUi(7, "Colin McFadden", "CM", me = true), PersonUi(9, "Sam B.", "SB", false), PersonUi(11, "Jess R.", "JR", false), PersonUi(12, "Kim L.", "KL", false))
    private val dk = OpenPackUi("p-dk", "OP DK", people, PackStatus.LIVE, finished = false, readOnly = false, pending = 0)

    @Test
    fun `how the pack stands is said under the chips, and the chip names the pack and who is here`() {
        assertEquals("Live · all changes synced", PackWords.status(dk))
        assertEquals("All changes synced", PackWords.status(dk.copy(status = PackStatus.POLLING)))
        assertEquals("Saving changes…", PackWords.status(dk.copy(pending = 2)))
        assertEquals("Offline · changes will send when back", PackWords.status(dk.copy(status = PackStatus.OFFLINE, pending = 2)))
        assertEquals("Opening…", PackWords.status(dk.copy(status = PackStatus.LOADING)))
        assertEquals("Cannot reach the pack", PackWords.status(dk.copy(status = PackStatus.ERROR)))
        assertEquals("Read-only for everyone", PackWords.status(dk.copy(finished = true)))
        assertEquals(
            "Workspace: Mission Pack OP DK. 4 people here now: Colin McFadden, Sam B., Jess R. and 1 more. Change workspace",
            PackWords.chipDescription(dk),
        )
        assertEquals("Workspace: Mission Pack OP DK. 1 person here now: Sam B.. Change workspace", PackWords.chipDescription(dk.copy(people = people.subList(1, 2))))
        assertEquals("Workspace: Personal. Change workspace", PackWords.chipDescription(null))
        assertEquals(
            "Workspace: Mission Pack OP DK. 3 people here now: Colin McFadden, Sam B. and Jess R.. Change workspace",
            PackWords.chipDescription(dk.copy(people = people.take(3))),
        )
        assertEquals(3, dk.others)
    }

    private val state = SwitcherUi(
        invites = listOf(InviteUi(5, "Alex Park", "OP IBIS", team = false, role = "Editor")),
        current = "p-dk",
        library = "Personal · 12 LZ/PZs, 4 route sets, 3 point sets",
        packs = listOf(
            PackRowUi("p-dk", "OP DK", "4 members · you are Owner", listOf("2 here now", "3 LZ/PZ · 2 route sets"), finished = false, current = true),
            PackRowUi("p-ex", "B CO FIELD EX", "18 members · you are Editor", emptyList(), finished = false, current = false),
            PackRowUi("p-rz", "OP RAZORBILL", "Finished Sep 22 · read-only", emptyList(), finished = true, current = false),
        ),
        teams = "B Co 2-10 AVN and 1 other team",
        loaded = true,
    )

    private fun show(state: SwitcherUi, mode: ThemeMode = ThemeMode.Dark, log: MutableList<String> = mutableListOf()) = compose.setContent {
        EzpzTheme(mode) {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                SwitcherContent(state, SwitcherActions(pick = { log += "pick $it" }, answer = { id, yes -> log += "answer $id $yes" }, close = { log += "close" }))
            }
        }
    }

    @Test
    fun `the switcher picks a workspace, answers an invitation and marks where edits go now`() {
        val log = mutableListOf<String>()
        show(state, log = log)
        compose.onNodeWithText("OP DK").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and hasText("OP DK", substring = true)).assertExists()
        compose.onNodeWithText("B CO FIELD EX").performClick()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithContentDescription("Accept the invitation to OP IBIS").performClick()
        compose.onNodeWithContentDescription("Decline the invitation to OP IBIS").performClick()
        compose.onNodeWithContentDescription("Close workspace switcher").performClick()
        assertEquals(listOf("pick p-ex", "pick null", "answer 5 true", "answer 5 false", "close"), log)
    }

    @Test
    fun `with no packs it says what a pack is`() {
        show(SwitcherUi(loaded = true))
        compose.onNodeWithText("No packs yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText(TEAMLESS).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and hasText("Library", substring = true)).assertExists()
    }

    @Test
    fun `before a load has answered it does not say there are no packs`() {
        show(SwitcherUi(loaded = false, error = "There is no connection."))
        compose.onNodeWithText("No packs yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("There is no connection.").assertIsDisplayed()
    }

    @Test
    fun `a pack open is where edits go, not the Library, whatever the list holds`() {
        show(SwitcherUi(current = "p-dk", loaded = false))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false) and hasText("Library", substring = true)).assertExists()
    }

    @Test
    fun `an answer that did not go through is said in the switcher`() {
        show(state.copy(answerError = "That invitation is no longer valid."))
        compose.onNodeWithText("That invitation is no longer valid.").assertIsDisplayed()
    }

    @Test
    fun `the invitation names the pack in bold, not the inviter of the same name`() {
        show(SwitcherUi(invites = listOf(InviteUi(3, "OP IBIS", "OP IBIS", team = false, role = "Editor")), loaded = true))
        val text = compose.onNodeWithText("OP IBIS invited you to OP IBIS as Editor").fetchSemanticsNode().config[SemanticsProperties.Text].first()
        val bold = text.spanStyles.single()
        assertEquals("OP IBIS invited you to ".length, bold.start)
        assertEquals(FontWeight.SemiBold, bold.item.fontWeight)
    }

    @Test
    fun `in a menu, which scrolls what it holds, the switcher is drawn and works`() {
        val log = mutableListOf<String>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box {
                    DropdownMenu(expanded = true, onDismissRequest = {}, modifier = Modifier.width(400.dp)) {
                        SwitcherContent(state, SwitcherActions(pick = { log += "pick $it" }))
                    }
                }
            }
        }
        compose.onNodeWithText("B CO FIELD EX").performClick()
        assertEquals(listOf("pick p-ex"), log)
    }

    @Test
    fun `the chip is one button named for the pack and who is here`() {
        var opened = 0
        compose.setContent { EzpzTheme(ThemeMode.Dark) { PackChip(dk, onClick = { opened++ }) } }
        compose.onNodeWithContentDescription(PackWords.chipDescription(dk)).performClick()
        assertEquals(1, opened)
    }

    @Test fun switcher() = shot("switcher")

    @Test fun switcherNight() = shot("switcher-night", ThemeMode.Night)

    @Test fun switcherLight() = shot("switcher-light", ThemeMode.Light)

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark) {
        show(state, mode)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun chip() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column(Modifier.background(androidx.compose.ui.graphics.Color(0xFF3A4A3A)).padding(16.dp)) {
                    PackChip(dk, onClick = {})
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    PackStatusLine(dk)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    PackStatusLine(dk.copy(pending = 2, status = PackStatus.OFFLINE))
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/pack-chip.png")
    }
}
