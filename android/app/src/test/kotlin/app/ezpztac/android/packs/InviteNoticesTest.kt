package app.ezpztac.android.packs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.missionpacks.InviteState
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackPerson
import app.ezpztac.network.PackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val JOINED = "You joined OP DK as an editor."
internal const val OFFLINE = "There is no connection to the server. Try again when you are back online."

internal fun joinedPack(): InviteAccepted = InviteAccepted(
    Invite(id = 1, role = "editor", status = "accepted", expiresAt = "2026-10-15T00:00:00Z", createdAt = "2026-10-08T00:00:00Z"),
    pack = PackSummary(
        uuid = "p-1", name = "OP DK", description = "", status = "active", role = "editor", owner = PackPerson(1, "Colin M."), headSeq = 0,
        seenSeq = 0, memberCount = 2, audienceCount = 2, itemCount = 0, itemCounts = PackItemCounts(0, 0, 0),
        createdAt = "2026-10-08T00:00:00Z", updatedAt = "2026-10-08T00:00:00Z",
    ),
)

/** What the person is told about an invitation link, and what each notice does. */
class InviteNoticeTest {
    @Test
    fun `each state of an invitation has its notice, and only a failure that may pass offers to try again`() {
        assertNull(inviteNoticeFor(InviteState.None))
        assertEquals(InviteNotice("Accepting the invitation…", null, staysUntilAnswered = true), inviteNoticeFor(InviteState.Accepting))
        assertEquals(InviteNotice("Waiting.", null, staysUntilAnswered = false), inviteNoticeFor(InviteState.Waiting("Waiting.")))
        assertEquals(InviteNotice(JOINED, null, staysUntilAnswered = false), inviteNoticeFor(InviteState.Joined(joinedPack(), JOINED)))
        assertEquals(InviteNotice(OFFLINE, "Try again", staysUntilAnswered = true), inviteNoticeFor(InviteState.Failed(OFFLINE, retryable = true)))
        assertEquals(InviteNotice("Gone.", null, staysUntilAnswered = false), inviteNoticeFor(InviteState.Failed("Gone.", retryable = false)))
    }
}

/** The notices as toasts, on a clock the test turns by hand: a snackbar's own time would otherwise run out at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class InviteNoticesTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private var state by mutableStateOf<InviteState>(InviteState.None)

    private fun show(first: InviteState) {
        state = first
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                val host = remember { SnackbarHostState() }
                Box(Modifier.fillMaxSize()) {
                    ToastHost(host, Modifier.align(Alignment.BottomCenter))
                    InviteNotices(state, host, onRetry = { log += "retry" }, onDismiss = { log += "dismiss" })
                }
            }
        }
        settle()
    }

    private fun settle() = repeat(20) { compose.mainClock.advanceTimeByFrame() }

    // A state written from the test is only seen by the composition once its change is announced, which idling would do and a clock turned
    // by hand does not.
    private fun becomes(next: InviteState) {
        state = next
        Snapshot.sendApplyNotifications()
        settle()
    }

    @Test
    fun `a pack joined is said, and goes away by itself after a while, put away`() {
        show(InviteState.Joined(joinedPack(), JOINED))
        compose.onNodeWithText(JOINED).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(11_000)
        settle()
        compose.onNodeWithText(JOINED).assertDoesNotExist()
        assertEquals(listOf("dismiss"), log)
    }

    @Test
    fun `every notice can be closed, which puts it away`() {
        show(InviteState.Waiting("Mission Packs are not turned on for your account yet, so the invitation is waiting."))
        compose.onNodeWithText("so the invitation is waiting", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        settle()
        assertEquals(listOf("dismiss"), log)
    }

    @Test
    fun `a failure that may pass stays until it is answered, and Try again asks again`() {
        show(InviteState.Failed(OFFLINE, retryable = true))
        compose.mainClock.advanceTimeBy(60_000)
        settle()
        compose.onNodeWithText(OFFLINE).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        settle()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `a failure for good offers nothing to try again`() {
        show(InviteState.Failed("That invitation was withdrawn.", retryable = false))
        compose.onNodeWithText("That invitation was withdrawn.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `closing the notice while the invitation is accepted puts nothing away, and the answer replaces it`() {
        show(InviteState.Accepting)
        compose.onNodeWithText("Accepting the invitation…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        settle()
        assertEquals(emptyList<String>(), log)

        becomes(InviteState.Joined(joinedPack(), JOINED))
        compose.onNodeWithText(JOINED).assertIsDisplayed()
    }

    @Test
    fun `a newer state replaces the notice on screen`() {
        show(InviteState.Accepting)
        becomes(InviteState.Failed(OFFLINE, retryable = true))
        compose.onNodeWithText("Accepting the invitation…").assertDoesNotExist()
        compose.onNodeWithText(OFFLINE).assertIsDisplayed()
        assertEquals(emptyList<String>(), log)                                                // replaced, not put away
    }
}
