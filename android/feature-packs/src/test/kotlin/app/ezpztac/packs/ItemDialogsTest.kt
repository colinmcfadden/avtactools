package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ezpztac.data.CopyIn
import app.ezpztac.data.LibraryCopier
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.network.PackChange
import app.ezpztac.network.PackItemSource
import app.ezpztac.network.PackPerson
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Updating a copy from its original and saving a pack's item to the Library (the design's PackDialogs B and D), and what the update says it replaces. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class ItemDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = ItemDialogActions(
        confirmUpdate = { log += "update" }, dismissUpdate = { log += "cancel update" }, saveName = { log += "name $it" }, confirmSave = { log += "save" },
        dismissSave = { log += "cancel save" },
    )
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val jess = PackChange(PackPerson(8, "Jess Reyes"), "Jess Reyes set the landing heading of LZ HAWK to 270°.", "2026-10-12T10:00:00")
    private val source = PackItemSource("lz", "orig-1", 1, "changed", "2026-10-03T09:12:00", 3, jess)
    private val update = UpdateUi("dk", "OP DK", "lz-1", "LZ HAWK (ROCK)", "lz", "orig-1", source = source, loading = false)
    private val save = SaveCopyUi("dk", "OP DK", "lz-2", "lz", "LZ IBIS (OWEST) copy")

    private fun show(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) = compose.setContent {
        EzpzTheme(mode) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.Center) { content() } }
    }

    // -- What the update says -----------------------------------------------------------------------------------------

    @Test
    fun `the update says when the original changed and what in the pack it replaces, in the web's words`() {
        assertEquals("Update “LZ HAWK” from Library?", UpdateWords.title("LZ HAWK"))
        assertEquals("Your Library version changed Oct 3 at 09:12. Updating replaces the pack copy with it.", UpdateWords.text(source, utc, now))
        assertEquals("Updating replaces the pack copy with your Library version.", UpdateWords.text(source.copy(originalUpdatedAt = null), utc, now))
        val three = UpdateWords.replaced(source, loading = false, me = 6)!!
        assertEquals("3 changes made in the pack will be replaced", three.bold)
        assertEquals(", including Jess R.’s last, “set the landing heading of LZ HAWK to 270°”. The history keeps what was there.", three.rest)
        assertEquals(", including your last, “set the landing heading of LZ HAWK to 270°”.", UpdateWords.lastChange(3, jess, me = 8))
        assertEquals(": Jess Reyes set the landing heading of LZ HAWK to 270°.", UpdateWords.lastChange(1, jess, me = 6))
        assertEquals("1 change made in the pack will be replaced", UpdateWords.replaced(source.copy(packChanges = 1), loading = false, me = 6)!!.bold)
        assertNull(UpdateWords.replaced(source.copy(packChanges = 0), loading = false, me = 6))
        assertNull(UpdateWords.replaced(null, loading = true, me = 6))                              // while it is asked for
        assertEquals("Changes made to it in the pack will be replaced. The history keeps what was there.", UpdateWords.replaced(null, loading = false, me = 6)!!.rest)
        assertEquals(".", UpdateWords.lastChange(2, null, me = 6))
    }

    @Test
    fun `the update dialog says it all and updates, the update in the error colour as the design draws it`() {
        show { UpdateContent(update, actions, zone = utc, now = now, me = 6) }
        compose.onNodeWithText("Update “LZ HAWK (ROCK)” from Library?").assertExists()
        compose.onNodeWithText("3 changes made in the pack will be replaced", substring = true).assertExists()
        compose.onNode(hasText("Update from original") and hasClickAction()).performClick()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(listOf("update", "cancel update"), log)
        compose.onRoot().captureRoboImage("build/screenshots/update-from-original.png")
    }

    @Test
    fun `while the update is sent nothing can be pressed, and why it did not go is said`() {
        show { UpdateContent(update.copy(sending = true, error = "The original is no longer in your Library."), actions, zone = utc, now = now) }
        compose.onNode(hasText("Updating…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Cancel") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("The original is no longer in your Library.").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun updateTabletShot() {
        show { UpdateContent(update, actions, Modifier.width(540.dp), tablet = true, zone = utc, now = now, me = 6) }
        compose.onRoot().captureRoboImage("build/screenshots/update-from-original-tablet.png")
    }

    // -- Save a copy --------------------------------------------------------------------------------------------------

    @Test
    fun `a copy is named in the design's words, the name cleared with one press, and saved`() {
        show { SaveCopyContent(save, actions) }
        compose.onNodeWithText("Save a copy to your Library").assertExists()
        compose.onNodeWithText("From OP DK.").assertExists()
        compose.onNodeWithText("Only you can see this copy. Later changes in the pack do not update it.").assertExists()
        compose.onNodeWithText("Saves to your Library").assertExists()
        compose.onNodeWithContentDescription("Clear name").performClick()
        compose.onNode(hasText("LZ IBIS (OWEST) copy") and hasSetTextAction()).performImeAction()
        compose.onNode(hasText("Save copy") and hasClickAction()).performClick()
        assertEquals(listOf("name ", "save", "save"), log)
        compose.onRoot().captureRoboImage("build/screenshots/save-copy.png")
    }

    @Test
    fun `a name refused is said at the field`() {
        show { SaveCopyContent(save.copy(name = "", nameError = "Give it a name."), actions) }
        compose.onNodeWithText("Give it a name.").assertExists()
    }

    @Test
    fun `while the copy is saved nothing can be pressed`() {
        show { SaveCopyContent(save.copy(sending = true), actions) }
        compose.onNode(hasText("Saving…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Cancel") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear name").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet's copy has no clear button, and says where it goes beside its buttons`() {
        show { SaveCopyContent(save, actions, Modifier.width(540.dp), tablet = true) }
        compose.onNodeWithContentDescription("Clear name").assertDoesNotExist()
        compose.onNodeWithText("Saves to your Library").assertExists()
        compose.onNode(hasText("Save copy") and hasClickAction()).assertIsEnabled()
        compose.onRoot().captureRoboImage("build/screenshots/save-copy-tablet.png")
    }

    // -- The finished banner's copy -----------------------------------------------------------------------------------

    @Test
    fun `a finished pack's banner saves a copy of the LZ open, and offers none without one`() {
        val finished = OpenPackUi("dk", "OP DK", emptyList(), app.ezpztac.missionpacks.PackStatus.LIVE, finished = true, readOnly = true, pending = 0, role = "editor")
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column(Modifier.width(412.dp)) {
                    FinishedBanner(finished, onDuplicate = {}, onReopen = {}, onSaveCopy = { log += "copy" }, zone = utc, now = now)
                }
            }
        }
        compose.onNode(hasText("Save a copy to Library") and hasClickAction()).performClick()
        assertEquals(listOf("copy"), log)
        compose.onRoot().captureRoboImage("build/screenshots/finished-save-copy.png")
    }

    @Test
    fun `a finished pack's banner with no LZ-PZ of its own open offers no copy, and a tablet's band offers one when there is`() {
        val finished = OpenPackUi("dk", "OP DK", emptyList(), app.ezpztac.missionpacks.PackStatus.LIVE, finished = true, readOnly = true, pending = 0, role = "editor")
        var copyable by mutableStateOf(false)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Column(Modifier.width(1000.dp)) {
                    if (copyable) FinishedBand(finished, onDuplicate = {}, onReopen = {}, onSaveCopy = { log += "band copy" }, zone = utc, now = now)
                    else FinishedBanner(finished, onDuplicate = {}, onReopen = {}, zone = utc, now = now)
                }
            }
        }
        compose.onNodeWithText("Save a copy to Library").assertDoesNotExist()
        compose.onNodeWithText("Duplicate as new pack").assertExists()
        copyable = true
        compose.waitForIdle()
        compose.onNode(hasText("Save a copy to Library") and hasClickAction()).performClick()
        assertEquals(listOf("band copy"), log)
    }

    @Test
    fun `the update dialog the app shows names the person's own last change as theirs`() {
        val item = PackItemView(
            "lz-1", "lz", "LZ HAWK", buildJsonObject { put("name", JsonPrimitive("LZ HAWK")) },
            info = buildJsonObject { put("source", buildJsonObject { put("kind", JsonPrimitive("lz")); put("uuid", JsonPrimitive("orig-1")) }) },
            pendingCreate = false,
        )
        val pack = buildJsonObject { put("uuid", JsonPrimitive("dk")); put("name", JsonPrimitive("OP DK")) }
        val session = PackSession(8, pack, JsonArray(emptyList()), emptyMap(), emptyMap(), emptyList(), 10, emptyList(), emptyList(), false, null, false, emptyMap())
        val door = object : PackDoor {
            override val user = MutableStateFlow<Int?>(8)
            override val open = MutableStateFlow<PackState?>(PackState("dk", app.ezpztac.missionpacks.PackStatus.LIVE, session, listOf(item), emptyList(), null))
            override suspend fun openPack(uuid: String) = Unit
            override suspend fun closePack() = Unit
        }
        val calls = object : PackItemCalls {
            override suspend fun source(pack: String, item: String, user: Int) = source
            override suspend fun updateFromOriginal(pack: String, item: String, user: Int) = Unit
            override suspend fun saveToLibrary(pack: String, kind: String, name: String, data: JsonElement): KeptOutcome = error("not here")
        }
        val copier = object : LibraryCopier {
            override suspend fun copyIn(pack: String, kind: RecordKind, uuid: String, item: String, user: Int): CopyIn = error("not here")
        }
        val model = PackItemCopyViewModel(door, calls, copier)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { ItemDialogsHost(model) } }
        model.askUpdate("lz-1")                                                                      // Jess Reyes (8) made the last change: this is Jess
        compose.waitForIdle()
        compose.onNodeWithText("including your last", substring = true).assertExists()
    }

    @Test
    fun lightShot() {
        show(ThemeMode.Light) { UpdateContent(update, actions, zone = utc, now = now, me = 6) }
        compose.onRoot().captureRoboImage("build/screenshots/update-from-original-light.png")
    }
}
