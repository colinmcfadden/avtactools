package app.ezpztac.briefs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.ezpztac.brief.BriefEdits
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The setup's screens (the design's Android tablet boards *brief setup* and *review and share*), laid out from OP DK's brief. `-Pezpz.screenshots` draws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-mdpi")
class BriefSetupTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val changes = mutableListOf<(JsonObject) -> JsonObject>()
    private val actions = BriefActions(
        close = { log += "close" }, show = { log += "show $it" }, next = { log += "next" }, edit = { changes += it }, refreshWeather = { log += "weather" },
        makePdf = { log += "make" }, share = { log += "share" }, save = { log += "save" },
    )

    private fun set(state: BriefUi) = compose.setContent { EzpzTheme(ThemeMode.Dark) { BriefSetupContent(state, actions, Modifier.fillMaxSize()) } }

    private fun ui(step: SetupStep = SetupStep.FLOW, pack: String? = "OP DK", pdf: PdfUi = PdfUi()) = BriefUi(open = true, step = step, brief = BriefFixtures.state(pack), pdf = pdf)

    /** The brief document after every change the screen asked for, made to OP DK's. */
    private fun applied(): JsonObject = changes.fold(BriefFixtures.document) { doc, change -> change(doc) }

    private fun JsonObject.flowRefs() = (this["flow"] as JsonArray).map { it.jsonObject["ref"]!!.jsonPrimitive.content }

    // -- The frame ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the setup says what it is of, in which format, and goes from step to step`() {
        set(ui())
        compose.onNodeWithText("Brief setup · OP DK").assertIsDisplayed()
        compose.onNodeWithText("Built from 5 LZ/PZs, 1 route set, 1 threat", substring = true).assertIsDisplayed()
        // The web's subtitle: the format's full title, and its source.
        compose.onNodeWithText("Non-tactical multi-ship / air movement brief · Aviation Branch Handbook", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Next: brief details").performClick()
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(listOf("next", "show REVIEW", "close"), log)
        compose.onNodeWithText("Hasty multi-ship brief").performClick()
        assertEquals("hasty", applied()["template"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        compose.onNodeWithText("Kept with OP DK for everyone in it.").assertIsDisplayed()
        compose.onNodeWithText("33 filled · 42 print as write-in lines").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/brief-flow.png")
    }

    @Test
    fun `outside a pack it is this session's brief, kept in the Library`() {
        set(BriefUi(open = true, brief = BriefFixtures.state(pack = null, save = app.ezpztac.data.BriefSave.SAVED)))
        compose.onNodeWithText("Brief setup · this session").assertIsDisplayed()
        compose.onNodeWithText("Saved to your Library").assertIsDisplayed()
    }

    @Test
    fun `while it opens it says so`() {
        set(BriefUi(open = true))
        compose.onNodeWithText("Opening the brief…").assertIsDisplayed()
        compose.onNodeWithText("Brief setup · this session").assertIsDisplayed()
    }

    // -- The flow and the constants -----------------------------------------------------------------------------------------------

    @Test
    fun `the flow's items move, change role, and say where each is briefed`() {
        set(ui())
        compose.onNodeWithText("LZ HAWK").assertIsDisplayed()
        compose.onNodeWithText("LZ · 16S GD 59833 44327").assertIsDisplayed()
        compose.onNodeWithContentDescription("Move LZ HAWK up").performClick()
        assertEquals(listOf("route:set-opdk/r-alderaan", "lz:lz-hawk", "lz:lz-bluebird"), applied().flowRefs().take(3))
        compose.onNodeWithContentDescription("Role of LZ HAWK").performClick()
        compose.onNodeWithText("FARP").performClick()
        val hawk = (applied()["flow"] as JsonArray).map { it.jsonObject }.first { it["ref"]!!.jsonPrimitive.content == "lz:lz-hawk" }
        assertEquals("FARP", hawk["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `constants are typed, taken away and added, and the threats turned off`() {
        set(ui())
        compose.onNodeWithContentDescription("Remove Enroute airspeed").performScrollTo().performClick()
        assertFalse(BriefEdits.constantsOf(applied(), BriefFixtures.state().format.constants).any { it["id"]!!.jsonPrimitive.content == "enrouteAirspeed" })
        compose.onNodeWithText("+ Your own").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Name of the constant")).performTextReplacement("Formation")
        compose.onNode(hasSetTextAction() and hasText("Its value")).performTextReplacement("Staggered left")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        val own = BriefEdits.constantsOf(applied(), BriefFixtures.state().format.constants).last()
        assertEquals(JsonPrimitive("Formation"), own["label"])
        assertEquals(JsonPrimitive("Staggered left"), own["value"])
        compose.onNodeWithText("Threats (1)").performScrollTo().performClick()
        assertFalse(BriefEdits.threatsOn(applied()))
    }

    // -- The details --------------------------------------------------------------------------------------------------------------

    @Test
    fun `the details go a paragraph at a time, each saying how it stands`() {
        set(ui(SetupStep.DETAILS))
        compose.onNodeWithText("1. Roll call").assertIsDisplayed()
        compose.onNodeWithText("All set").assertIsDisplayed()
        compose.onNodeWithText("21 to add").assertIsDisplayed()
        compose.onNodeWithText("Service support").performClick()
        compose.onNodeWithText("5. Service support").assertIsDisplayed()
        compose.onNodeWithText("Use standard for all").performClick()
        listOf("5.a.ii", "5.a.iv", "5.a.v").forEach { key ->
            assertEquals(key, JsonPrimitive("standard"), BriefEdits.answerOf(applied(), key)!!["mode"])
        }
        compose.onRoot().captureRoboImage("build/screenshots/brief-details.png")
    }

    @Test
    fun `an answer typed is kept once the person stops typing`() {
        set(ui(SetupStep.DETAILS))
        compose.onNodeWithText("Situation").performClick()
        compose.onNode(hasSetTextAction() and hasText("Minimum weather")).performScrollTo().performTextReplacement("400-1 night")
        assertTrue("committed before the person stopped", changes.isEmpty())
        compose.mainClock.advanceTimeBy(DRAFT_DELAY_MS + 100)
        compose.waitForIdle()
        assertEquals(JsonPrimitive("400-1 night"), BriefEdits.answerOf(applied(), "2.b")!!["value"])
    }

    @Test
    fun `words typed in an answer stay with it when another paragraph is shown before they are kept`() {
        set(ui(SetupStep.DETAILS))
        compose.onNodeWithText("Situation").performClick()
        compose.onNode(hasSetTextAction() and hasText("Minimum weather")).performScrollTo().performTextReplacement("400-1 night")
        compose.onNodeWithText("Mission and intent").performClick()                       // before the pause: the field still has the focus
        compose.mainClock.advanceTimeBy(DRAFT_DELAY_MS + 100)
        compose.waitForIdle()
        assertEquals(JsonPrimitive("400-1 night"), BriefEdits.answerOf(applied(), "2.b")!!["value"])
        assertTrue("the next paragraph's answers are their own", listOf("3.a", "3.b", "3.c").none { BriefEdits.answerOf(applied(), it)?.get("value") == JsonPrimitive("400-1 night") })
        assertTrue(compose.onAllNodesWithText("400-1 night").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a script reset is not put back by what was typed in it`() {
        set(ui(SetupStep.DETAILS))
        compose.onNodeWithText("Mission and intent").performClick()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("The script")).performScrollTo().performTextReplacement("OUR OWN WORDS")
        compose.onNodeWithText(SetupWords.RESET_SCRIPT).performScrollTo().performClick()  // before the pause
        compose.mainClock.advanceTimeBy(DRAFT_DELAY_MS + 100)
        compose.waitForIdle()
        assertFalse(BriefEdits.scriptEdited(applied(), "3"))
    }

    @Test
    @Config(qualifiers = "w840dp-h640dp-mdpi")
    fun `in a window as narrow as the tablet's frame the steps keep their room`() {
        var state by mutableStateOf(ui())
        compose.setContent { EzpzTheme(ThemeMode.Dark) { BriefSetupContent(state, actions, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("Hasty multi-ship brief").assertIsDisplayed()               // the format under the title
        compose.onNodeWithText("Brief setup · OP DK").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/brief-flow-narrow.png")
        state = ui(SetupStep.DETAILS)
        compose.onNodeWithText("Mission and intent").performClick()
        compose.onNodeWithText("Script · 3").performScrollTo().assertIsDisplayed()          // under the answers
        compose.onRoot().captureRoboImage("build/screenshots/brief-details-narrow.png")
        state = ui(SetupStep.REVIEW, pdf = PdfUi(made = MadePdf(FakePdf(3), byteArrayOf(1), BriefFixtures.model, "OP DK brief.pdf")))
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithText("Nav logs").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/brief-review-narrow.png")
    }

    @Test
    fun `the weather at the planned times can be asked for again where it is briefed`() {
        set(ui(SetupStep.DETAILS))
        compose.onNodeWithText("Situation").performClick()
        compose.onNodeWithText("No weather yet").assertIsDisplayed()
        compose.onNodeWithText("Refresh weather").performClick()
        assertEquals(listOf("weather"), log)
    }

    // -- The review ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `the review says what to check, and its options change the PDF`() {
        set(ui(SetupStep.REVIEW))
        compose.onNodeWithText("No route in the flow reaches FARP BUCEES before it, so it has no arrival time.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nav logs").performScrollTo().performClick()
        assertEquals(JsonPrimitive(false), BriefEdits.option(applied(), "navLogs"))
        compose.onNode(hasText("Topo") and hasAnyAncestor(hasContentDescription("Map in the figures"))).performScrollTo().performClick()
        assertEquals(JsonPrimitive("topo"), BriefEdits.option(applied(), "figureMap"))
        compose.onNodeWithText("1:250,000").performScrollTo().performClick()
        assertEquals(JsonPrimitive(250000), BriefEdits.option(applied(), "stripScale"))
        compose.onNodeWithText("12 strips, each 21.5 by 5.5 km of ground, with the 1 km MGRS grid.").performScrollTo().assertIsDisplayed()
        // Before the PDF is made: the packet by section, never a page number of its own.
        compose.onNodeWithText("In the packet").assertIsDisplayed()
        compose.onNodeWithText("Make the PDF").performClick()
        assertEquals(listOf("make"), log)
    }

    @Test
    fun `the PDF is shown page by page once made, and shared or saved from the foot`() {
        val made = MadePdf(FakePdf(3), byteArrayOf(1), BriefFixtures.model, "OP DK brief.pdf")
        set(ui(SetupStep.REVIEW, pdf = PdfUi(made = made)))
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithText("Page 3").assertIsDisplayed()
        compose.onNodeWithText("3 pages").assertIsDisplayed()
        compose.onNodeWithText("Share PDF").performClick()
        compose.onNodeWithText("Save to device").performClick()
        assertEquals(listOf("share", "save"), log)
        compose.onRoot().captureRoboImage("build/screenshots/brief-review.png")
    }

    @Test
    fun `a PDF being made, one that failed, and one of an older brief each say so`() {
        var state by mutableStateOf(ui(SetupStep.REVIEW, pdf = PdfUi(making = true)))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { BriefSetupContent(state, actions, Modifier.fillMaxSize()) } }
        compose.onAllNodesWithText(SetupWords.MAKING).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        state = ui(SetupStep.REVIEW, pdf = PdfUi(failure = "No connection to the server: the PDF needs one."))
        compose.onNodeWithText("No connection to the server: the PDF needs one.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        val older = MadePdf(FakePdf(2), byteArrayOf(1), JsonObject(BriefFixtures.model + ("generatedAt" to JsonPrimitive("then"))), "OP DK brief.pdf")
        state = ui(SetupStep.REVIEW, pdf = PdfUi(made = older))
        compose.onNodeWithText(SetupWords.STALE).assertIsDisplayed()
        compose.onNodeWithText("Make it again").performClick()
        assertEquals(listOf("make", "make"), log)
    }
}
