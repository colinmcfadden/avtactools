package app.ezpztac.briefs

import android.net.Uri
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The setup's steps, the PDF made of the brief shown, and Share and Save (docs/MISSION_BRIEF.md, B8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BriefViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private class Env(val door: FakeDoor, val pdfs: FakePdfs, val writes: MutableList<Pair<Uri, ByteArray>>, val discards: MutableList<Uri>, val model: BriefViewModel) {
        val notices = mutableListOf<String>()
        val exports = mutableListOf<BriefExport>()
        val saves = mutableListOf<String>()
    }

    private fun vmTest(pack: String? = "OP DK", block: suspend TestScope.(Env) -> Unit) = runTest(dispatcher) {
        val door = FakeDoor(BriefFixtures.state(pack))
        val pdfs = FakePdfs()
        val writes = mutableListOf<Pair<Uri, ByteArray>>()
        val discards = mutableListOf<Uri>()
        val files = object : BriefFileWriter {
            override suspend fun write(target: Uri, bytes: ByteArray) {
                writes += target to bytes
            }

            override suspend fun discard(target: Uri) {
                discards += target
            }
        }
        val model = BriefViewModel(door, pdfs, files)
        val env = Env(door, pdfs, writes, discards, model)
        backgroundScope.launch { model.notices.collect { env.notices += it } }
        backgroundScope.launch { model.exports.collect { env.exports += it } }
        backgroundScope.launch { model.saves.collect { env.saves += it } }
        runCurrent()
        block(env)
    }

    /** The brief settled: what the PDF waits for before it is made. */
    private fun TestScope.settle() {
        advanceTimeBy(BriefViewModel.SETTLE_MS + 1)
        runCurrent()
    }

    @Test
    fun `the setup opens at its first step, and closing writes what waits and lets go of the PDF`() = vmTest { env ->
        env.model.open()
        runCurrent()
        assertTrue(env.model.state.value.open)
        assertEquals(SetupStep.FLOW, env.model.state.value.step)
        assertEquals(listOf("open"), env.door.log)
        env.model.show(SetupStep.REVIEW)
        settle()
        env.model.close()
        runCurrent()
        assertFalse(env.model.state.value.open)
        assertEquals(listOf("open", "close"), env.door.log)
        assertTrue("the PDF shown is let go", env.pdfs.opened.single().closed)
        assertNull(env.model.state.value.pdf.made)
    }

    @Test
    fun `the review makes the PDF of the brief shown the first time it shows, and not again`() = vmTest { env ->
        env.model.open()
        env.model.next()
        runCurrent()
        assertEquals(SetupStep.DETAILS, env.model.state.value.step)
        assertTrue(env.door.printed.isEmpty())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        env.door.printGate = gate
        env.model.next()
        runCurrent()
        assertTrue(env.model.state.value.pdf.making)
        gate.complete(Unit)
        settle()
        val made = env.model.state.value.pdf.made!!
        assertSame(BriefFixtures.model, env.door.printed.single())
        assertSame(BriefFixtures.model, made.model)
        assertEquals("OP DK brief.pdf", made.fileName)
        assertFalse(env.model.state.value.stale)
        env.model.show(SetupStep.FLOW)
        env.model.show(SetupStep.REVIEW)
        runCurrent()
        assertEquals(1, env.door.printed.size)
    }

    @Test
    fun `a PDF made of an older brief is said to be so, and Share makes it again before handing it on`() = vmTest { env ->
        env.model.open()
        env.model.show(SetupStep.REVIEW)
        settle()
        val changed = JsonObject(BriefFixtures.model + ("generatedAt" to JsonPrimitive("2026-10-16T19:00:00.000Z")))
        env.door.mutableState.value = BriefFixtures.state(model = changed)
        runCurrent()
        assertTrue(env.model.state.value.stale)
        env.model.share()
        settle()
        assertEquals(2, env.door.printed.size)
        assertSame(changed, env.door.printed.last())
        assertFalse(env.model.state.value.stale)
        assertTrue("the older PDF is let go", env.pdfs.opened.first().closed)
        assertEquals(listOf("OP DK brief.pdf"), env.exports.map { it.fileName })
    }

    @Test
    fun `Share hands on the PDF made of the brief shown, as it is`() = vmTest { env ->
        env.model.open()
        env.model.show(SetupStep.REVIEW)
        settle()
        env.model.share()
        runCurrent()
        assertEquals(1, env.door.printed.size)                                           // nothing changed: not made again
        assertTrue(env.door.pdfBytes.contentEquals(env.exports.single().bytes))
    }

    @Test
    fun `Save asks where with the PDF's name, and writes it there`() = vmTest(pack = null) { env ->
        env.model.open()
        env.model.save()
        settle()
        assertEquals(listOf("Mission brief.pdf"), env.saves)
        val target = Uri.parse("content://documents/brief.pdf")
        env.model.saveTo(target)
        runCurrent()
        assertEquals(target, env.writes.single().first)
        assertTrue(env.door.pdfBytes.contentEquals(env.writes.single().second))
        assertEquals(listOf("Saved \"Mission brief.pdf\"."), env.notices)
        env.model.saveTo(null)                                                           // chose nowhere: nothing
        runCurrent()
        assertEquals(1, env.writes.size)
    }

    @Test
    fun `a PDF that cannot be made says why in the app's words`() = vmTest { env ->
        env.door.printFailure = ApiException(503, "pdf_unavailable", "The PDF is not available on this server.")
        env.model.open()
        env.model.show(SetupStep.REVIEW)
        settle()
        assertEquals("The PDF is not available on this server yet.", env.model.state.value.pdf.failure)
        assertFalse(env.model.state.value.pdf.making)
        assertNull(env.model.state.value.pdf.made)
        env.door.printFailure = NetworkException("no route", requestMayHaveBeenSent = false)
        env.model.makePdf()
        settle()
        assertEquals("No connection to the server: the PDF needs one.", env.model.state.value.pdf.failure)
        env.door.printFailure = null
        env.model.share()
        settle()
        assertNull(env.model.state.value.pdf.failure)
        assertEquals(1, env.exports.size)
    }

    @Test
    fun `what the pack would not take is told`() = vmTest { env ->
        env.door.refusals.emit("The pack would not take that change to the brief.")
        runCurrent()
        assertEquals(listOf("The pack would not take that change to the brief."), env.notices)
    }

    @Test
    fun `a field written as the setup closes still reaches the brief`() = vmTest { env ->
        env.model.open()
        env.model.edit { it }
        env.model.close()
        env.model.edit { it }                                                    // what was typed in a field, written as it leaves the screen
        runCurrent()
        assertEquals(2, env.door.changes.size)
    }

    @Test
    fun `a brief closed by itself, for another account, takes the setup and its PDF with it`() = vmTest { env ->
        env.model.open()
        env.model.show(SetupStep.REVIEW)
        settle()
        assertNotNull(env.model.state.value.pdf.made)
        env.door.mutableOpened.value = false                                      // the account changed
        runCurrent()
        assertFalse(env.model.state.value.open)
        assertTrue("the last person's PDF is let go", env.pdfs.opened.single().closed)
        assertEquals(listOf("open"), env.door.log)                                // closed by the brief itself, not asked to close again
    }

    @Test
    fun `the PDF is made of the brief as it settles, with a field written as its step left the screen`() = vmTest { env ->
        env.model.open()
        env.model.show(SetupStep.REVIEW)
        runCurrent()
        val answered = JsonObject(BriefFixtures.model + ("generatedAt" to JsonPrimitive("2026-10-16T19:00:00.000Z")))
        env.door.mutableState.value = BriefFixtures.state(model = answered)              // the field left as the review showed, compiled a moment later
        settle()
        assertSame(answered, env.door.printed.single())
        assertFalse(env.model.state.value.stale)
    }

    @Test
    fun `a place chosen to save in when there is no PDF any more is let go, and the person told`() = vmTest { env ->
        val target = Uri.parse("content://documents/brief.pdf")
        env.model.saveTo(target)                                                          // the app was ended while the picker was up
        runCurrent()
        assertEquals(listOf(target), env.discards)
        assertTrue(env.writes.isEmpty())
        assertEquals(listOf(BriefViewModel.NOTHING_TO_SAVE), env.notices)
    }

    @Test
    fun `a brief that cannot be compiled is said`() = vmTest { env ->
        env.door.mutableState.value = null
        env.door.mutableFailed.value = true
        env.model.open()
        runCurrent()
        assertTrue(env.model.state.value.failed)
    }

    @Test
    fun `the PDF is named for the mission in a pack, with only what a file name keeps, and Mission brief outside one`() {
        val pack = BriefFixtures.state(pack = "OP DK")
        assertEquals("OP DK brief.pdf", BriefViewModel.fileNameOf(pack))
        val odd = BriefFixtures.state(model = JsonObject(BriefFixtures.model + ("mission" to JsonObject(mapOf("name" to JsonPrimitive("OP/DK: “2” ✈"))))))
        assertEquals("OPDK 2 brief.pdf", BriefViewModel.fileNameOf(odd))
        val nothing = BriefFixtures.state(model = JsonObject(BriefFixtures.model + ("mission" to JsonObject(mapOf("name" to JsonPrimitive("✈✈"))))))
        assertEquals("Mission brief.pdf", BriefViewModel.fileNameOf(nothing))
        assertEquals("Mission brief.pdf", BriefViewModel.fileNameOf(BriefFixtures.state(pack = null)))
    }
}
