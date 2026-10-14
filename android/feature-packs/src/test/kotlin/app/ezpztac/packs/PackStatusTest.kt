package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.missionpacks.PackStatus
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

/**
 * How an open pack stands, on its chip (the owner's decision of 2026-10-13): a shape of its own for each state where the group icon was, said in words to a
 * screen reader, and said under the phone's row only while it wants attention, once that has lasted two seconds. `-Pezpz.screenshots` draws the chip in each.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class PackStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val people = listOf(PersonUi(7, "Colin McFadden", "CM", me = true), PersonUi(9, "Sam B.", "SB", false))
    private val dk = OpenPackUi("p-dk", "OP DK", people, PackStatus.LIVE, finished = false, readOnly = false, pending = 0)

    /** Every state the chip tells apart, with what it is called here. */
    private val states = listOf(
        "live" to dk,
        "polling" to dk.copy(status = PackStatus.POLLING),
        "saving" to dk.copy(pending = 2),
        "offline" to dk.copy(status = PackStatus.OFFLINE),
        "offline, changes waiting" to dk.copy(status = PackStatus.OFFLINE, pending = 2),
        "opening" to dk.copy(status = PackStatus.LOADING),
        "paused" to dk.copy(status = PackStatus.PAUSED, pending = 1),
        "cannot reach" to dk.copy(status = PackStatus.ERROR),
        "gone" to dk.copy(status = PackStatus.GONE),
        "finished" to dk.copy(finished = true, readOnly = true),
    )

    @Test
    fun `each state has a shape of its own in its tone, never colour alone, read as the words are`() {
        val standings = states.map { (_, pack) -> PackStanding.of(pack) }
        assertEquals(
            listOf(
                PackStanding.SYNCED, PackStanding.SYNCED, PackStanding.SAVING, PackStanding.OFFLINE, PackStanding.OFFLINE, PackStanding.OPENING,
                PackStanding.PAUSED, PackStanding.UNREACHABLE, PackStanding.GONE, PackStanding.FINISHED,
            ),
            standings,
        )
        assertEquals(EzpzIcons.cloudDone, PackStanding.SYNCED.icon)
        assertEquals(EzpzIcons.cloudUpload, PackStanding.SAVING.icon)
        assertEquals(EzpzIcons.cloudOff, PackStanding.OFFLINE.icon)
        assertEquals(EzpzIcons.hourglassTop, PackStanding.OPENING.icon)
        assertEquals(EzpzIcons.pauseFill, PackStanding.PAUSED.icon)                                    // two bars, filled: the outlined form reads as two zeroes
        assertEquals(EzpzIcons.error, PackStanding.UNREACHABLE.icon)
        assertEquals(EzpzIcons.error, PackStanding.GONE.icon)
        assertEquals(EzpzIcons.groups, PackStanding.FINISHED.icon)                                     // as before: the outlined group icon
        // A shape for each tone: two states sharing a shape share a tone, so a shape never needs its colour to be told apart.
        PackStanding.entries.groupBy { it.icon }.values.forEach { same -> assertEquals(1, same.map { it.tone }.toSet().size) }
        assertEquals(PackTone.SUCCESS, PackStanding.SYNCED.tone)
        assertEquals(listOf(PackTone.WARNING), listOf(PackStanding.SAVING, PackStanding.OFFLINE, PackStanding.OPENING, PackStanding.PAUSED).map { it.tone }.distinct())
        assertEquals(listOf(PackTone.ERROR), listOf(PackStanding.UNREACHABLE, PackStanding.GONE).map { it.tone }.distinct())
        // A finished pack that cannot be reached shows that, as its words say it first.
        assertEquals(PackStanding.UNREACHABLE, PackStanding.of(dk.copy(finished = true, status = PackStatus.ERROR)))
        assertEquals("Cannot reach the pack", PackWords.status(dk.copy(finished = true, status = PackStatus.ERROR)))
    }

    @Test
    fun `the chip draws each state's own shape, in its tone`() {
        var shown by mutableStateOf(dk)
        var tones = emptyMap<PackTone, Int>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                val each = PackTone.entries.associateWith { toneColor(it).toArgb() }
                SideEffect { tones = each }
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(8.dp).testTag("chip")) { PackChip(shown, onClick = {}) }
            }
        }
        // Each state's chip as drawn, nobody's faces in it: what tells the states apart is the shape where the group icon was, and its colour.
        val drawn = states.map { (name, pack) ->
            compose.runOnIdle { shown = pack.copy(people = emptyList()) }
            val image = compose.onNodeWithTag("chip").captureToImage().asAndroidBitmap()
            val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            Triple(name, PackStanding.of(pack), pixels)
        }
        drawn.forEach { (name, standing, pixels) ->
            drawn.forEach { (other, otherStanding, otherPixels) ->
                // Cannot reach and gone are one shape in one colour (the words under the row tell them apart); every other two states differ.
                val alike = standing.icon == otherStanding.icon && standing.tone == otherStanding.tone
                assertEquals("$name and $other look alike only when they are shown alike", alike, pixels.contentEquals(otherPixels))
            }
            val own = tones.getValue(standing.tone)
            assertTrue("$name is drawn in its tone", pixels.count { it == own } >= 20)
            // The status colours appear nowhere else on the chip: one that is not this state's means the shape was drawn in the wrong one.
            (listOf(PackTone.SUCCESS, PackTone.WARNING, PackTone.ERROR) - standing.tone).forEach { tone ->
                assertEquals("$name has nothing in the $tone colour", 0, pixels.count { it == tones.getValue(tone) })
            }
        }
    }

    @Test
    fun `only offline, paused, cannot reach and no longer available ask for the words under the row`() {
        val asking = states.filter { (_, pack) -> PackStanding.of(pack).attention }.map { it.first }
        assertEquals(listOf("offline", "offline, changes waiting", "paused", "cannot reach", "gone"), asking)
    }

    @Test
    fun `the chip says how the pack stands in words, after its name`() {
        val said = states.associate { (name, pack) -> name to PackWords.chipDescription(pack.copy(people = emptyList())) }
        assertEquals("Workspace: Mission Pack OP DK, live, all changes synced. Change workspace", said["live"])
        assertEquals("Workspace: Mission Pack OP DK, all changes synced. Change workspace", said["polling"])
        assertEquals("Workspace: Mission Pack OP DK, saving changes. Change workspace", said["saving"])
        assertEquals("Workspace: Mission Pack OP DK, offline. Change workspace", said["offline"])
        assertEquals("Workspace: Mission Pack OP DK, offline, changes will send when back. Change workspace", said["offline, changes waiting"])
        assertEquals("Workspace: Mission Pack OP DK, opening. Change workspace", said["opening"])
        assertEquals("Workspace: Mission Pack OP DK, paused, changes kept on this device. Change workspace", said["paused"])
        assertEquals("Workspace: Mission Pack OP DK, cannot reach the pack. Change workspace", said["cannot reach"])
        assertEquals("Workspace: Mission Pack OP DK, no longer available. Change workspace", said["gone"])
        assertEquals("Workspace: Mission Pack OP DK, finished and read-only. Change workspace", said["finished"])
        assertEquals(
            "Workspace: Mission Pack OP DK, finished and read-only, cannot reach the pack. Change workspace",
            PackWords.chipDescription(dk.copy(people = emptyList(), finished = true, status = PackStatus.ERROR)),
        )
        assertEquals(
            "Workspace: Mission Pack OP DK, all changes synced. 2 people here now: Colin McFadden and Sam B.. Change workspace",
            PackWords.chipDescription(dk.copy(status = PackStatus.POLLING)),
        )
    }

    @Test
    fun `a tablet's rail tile says who is here, its line under the search bar how the pack stands`() {
        assertEquals("Workspace: Mission Pack OP DK. 2 people here now: Colin McFadden and Sam B.. Change workspace", PackWords.railDescription(dk.copy(status = PackStatus.OFFLINE)))
        assertEquals("Workspace: Mission Pack OP DK, finished. Change workspace", PackWords.railDescription(dk.copy(finished = true)))
        assertEquals("Workspace: Personal. Change workspace", PackWords.railDescription(null))
    }

    // -- The words under the phone's row --------------------------------------------------------------------------------------------

    private var pack by mutableStateOf<OpenPackUi?>(null)

    private fun showLine(first: OpenPackUi?) {
        pack = first
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                val attention = rememberPackAttention(pack)
                PackAttentionLine(pack?.takeIf { attention })
            }
        }
        settle()
    }

    private fun settle() = repeat(4) { compose.mainClock.advanceTimeByFrame() }

    // A state written from the test is seen by the composition once its change is announced, which idling would do and a clock turned by hand does not.
    private fun becomes(next: OpenPackUi?) {
        pack = next
        Snapshot.sendApplyNotifications()
        settle()
    }

    private fun after(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        settle()
    }

    @Test
    fun `offline is said once it has lasted two seconds, and goes as soon as the pack is back`() {
        val offline = dk.copy(status = PackStatus.OFFLINE)
        showLine(offline)
        compose.onNodeWithText("Offline").assertDoesNotExist()
        after(ATTENTION_AFTER_MS - 200)
        compose.onNodeWithText("Offline").assertDoesNotExist()
        after(250)
        compose.onNodeWithText("Offline").assertIsDisplayed()
        becomes(dk)
        compose.onNodeWithText("Offline").assertDoesNotExist()
        compose.onNodeWithText("Live · all changes synced").assertDoesNotExist()
    }

    @Test
    fun `a blip says nothing, and each time the wait starts again`() {
        showLine(dk)
        becomes(dk.copy(status = PackStatus.OFFLINE))
        after(1_500)
        becomes(dk)                                                                                     // back before two seconds
        becomes(dk.copy(status = PackStatus.OFFLINE))
        after(1_500)
        compose.onNodeWithText("Offline").assertDoesNotExist()                                        // three seconds offline in all, never two in a row
        after(600)
        compose.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test
    fun `paused, cannot reach and no longer available are said after two seconds, and a line said stays from one to the next in its words`() {
        showLine(dk.copy(status = PackStatus.PAUSED))
        after(ATTENTION_AFTER_MS + 100)
        compose.onNodeWithText("Paused · changes kept on this device").assertIsDisplayed()
        becomes(dk.copy(status = PackStatus.ERROR))
        compose.onNodeWithText("Cannot reach the pack").assertIsDisplayed()                            // said already: no wait from one to the next
        becomes(dk.copy(status = PackStatus.GONE))
        compose.onNodeWithText("No longer available").assertIsDisplayed()
        becomes(dk.copy(status = PackStatus.OFFLINE, pending = 3))
        compose.onNodeWithText("Offline · changes will send when back").assertIsDisplayed()
        becomes(dk.copy(uuid = "p-other", status = PackStatus.OFFLINE))                               // another pack starts again
        compose.onNodeWithText("Offline").assertDoesNotExist()
        after(ATTENTION_AFTER_MS + 100)
        compose.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test
    fun `synced, saving, opening and finished are never said under the row`() {
        listOf(dk, dk.copy(status = PackStatus.POLLING), dk.copy(pending = 4), dk.copy(status = PackStatus.LOADING), dk.copy(finished = true, readOnly = true)).forEachIndexed { i, each ->
            if (i == 0) showLine(each) else becomes(each)
            after(10_000)
            compose.onNodeWithText(PackWords.status(each)).assertDoesNotExist()
        }
    }

    @Test
    fun `the line appears in a live region that is there before it, so a screen reader hears it come`() {
        showLine(dk)
        val region = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        assertEquals(1, compose.onAllNodes(region).fetchSemanticsNodes().size)                          // there, and empty
        becomes(dk.copy(status = PackStatus.OFFLINE))
        after(ATTENTION_AFTER_MS + 100)
        compose.onNodeWithText("Offline").assertIsDisplayed()
        assertEquals("one region, the line inside it", 1, compose.onAllNodes(region).fetchSemanticsNodes().size)
        assertTrue(SemanticsProperties.LiveRegion !in compose.onNodeWithText("Offline", useUnmergedTree = true).fetchSemanticsNode().config)
    }

    @Test
    fun `a tablet's line keeps its own live region`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { PackStatusLine(dk) } }
        assertFalse(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Live · all changes synced").assertIsDisplayed()
    }

    // -- Pictures: the chip in each state ------------------------------------------------------------------------------------------

    private fun gallery(name: String, mode: ThemeMode) {
        compose.setContent {
            EzpzTheme(mode) {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    states.forEach { (_, each) -> PackChip(each, onClick = {}) }
                    // And the line under the row, for a state that wants attention.
                    PackAttentionLine(dk.copy(status = PackStatus.OFFLINE, pending = 2))
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/pack-chip-states-$name.png")
    }

    @Test fun chipStatesDark() = gallery("dark", ThemeMode.Dark)

    @Test fun chipStatesNight() = gallery("night", ThemeMode.Night)

    @Test
    @Config(fontScale = 1.3f)
    fun chipStatesDarkLarge() = gallery("dark-font-1.3", ThemeMode.Dark)

    @Test
    @Config(fontScale = 1.3f)
    fun chipStatesNightLarge() = gallery("night-font-1.3", ThemeMode.Night)

    @Test
    @Config(fontScale = 2.0f)
    fun chipStatesDarkLargest() = gallery("dark-font-2.0", ThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun chipStatesNightLargest() = gallery("night-font-2.0", ThemeMode.Night)
}
