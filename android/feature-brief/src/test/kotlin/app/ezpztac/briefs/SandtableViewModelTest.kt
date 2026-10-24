package app.ezpztac.briefs

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The live sandtable's state (docs/MISSION_BRIEF.md §2.8, B9; the web's `useSandtable`): begun from the review, its steps, the step followed, and how it ends. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SandtableViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private class Env(val door: FakeDoor, val pdfs: FakePdfs, val model: BriefViewModel) {
        val shown: SandtableShown? get() = model.sandtable.value
        val ui: SandtableUi? get() = model.state.value.presenting
    }

    private fun vmTest(pack: String? = "OP DK", block: suspend TestScope.(Env) -> Unit) = runTest(dispatcher) {
        val door = FakeDoor(BriefFixtures.state(pack))
        val pdfs = FakePdfs()
        val files = object : BriefFileWriter {
            override suspend fun write(target: Uri, bytes: ByteArray) = Unit
        }
        val model = BriefViewModel(door, pdfs, files)
        runCurrent()
        block(Env(door, pdfs, model))
    }

    private val ids = (BriefFixtures.model["steps"] as JsonArray).map { (it as JsonObject)["id"]!!.jsonPrimitive.content }

    /** OP DK's model with the steps [gone] left out. */
    private fun without(vararg gone: String): JsonObject =
        JsonObject(BriefFixtures.model + ("steps" to JsonArray((BriefFixtures.model["steps"] as JsonArray).filter { (it as JsonObject)["id"]!!.jsonPrimitive.content !in gone })))

    @Test
    fun `presenting from the review closes the setup, keeps the brief open and begins at the first step`() = vmTest { env ->
        env.model.workspaceIs("p-1")
        env.model.open()
        env.model.show(SetupStep.REVIEW)
        advanceTimeBy(BriefViewModel.SETTLE_MS + 1)
        runCurrent()
        val pdf = env.pdfs.opened.single()
        env.model.present(narrow = false)
        runCurrent()
        assertFalse(env.model.state.value.open)
        assertEquals(SandtableUi(stepId = "1", anchor = 0, stepsOpen = true, constantsOpen = true, pack = "p-1"), env.ui)
        // The brief stays open (it is what the sandtable shows): nothing closed it.
        assertEquals(listOf("open", "open"), env.door.log)
        assertTrue("the review's PDF is let go", pdf.closed)
        val shown = env.shown!!
        assertEquals(0, shown.index)
        assertEquals("Step 1 of 18", shown.current!!.position)
        assertEquals("OP DK · Air movement brief", shown.title)
        assertEquals("1", shown.view!!.stepId)
    }

    @Test
    fun `the steps go forward and back, never past either end`() = vmTest { env ->
        env.model.present(narrow = false)
        runCurrent()
        env.model.nextStep()
        runCurrent()
        assertEquals(1, env.shown!!.index)
        env.model.previousStep()
        env.model.previousStep()
        runCurrent()
        assertEquals(0, env.shown!!.index)
        env.model.lastStep()
        runCurrent()
        assertEquals(17, env.shown!!.index)
        env.model.nextStep()
        runCurrent()
        assertEquals(17, env.shown!!.index)
        env.model.firstStep()
        runCurrent()
        assertEquals(0, env.shown!!.index)
        env.model.goToStep(5)
        runCurrent()
        assertEquals("4.c@route:set-opdk/r-alderaan", env.ui!!.stepId)
        env.model.goToStep(99)
        env.model.goToStep(-1)
        runCurrent()
        assertEquals(5, env.shown!!.index)
    }

    @Test
    fun `a step is followed by its id when the brief changes under it, and one that goes leaves the presenter at its place`() = vmTest { env ->
        env.model.present(narrow = false)
        runCurrent()
        val hawk = ids.indexOf("4.e@lz:lz-hawk")
        env.model.goToStep(hawk)
        runCurrent()
        // A step before it went (someone else's edit): the same step is shown, now one place up.
        env.door.mutableState.value = BriefFixtures.state(model = without("3"))
        runCurrent()
        assertEquals("4.e@lz:lz-hawk", env.shown!!.current!!.id)
        assertEquals(SandtableUi(stepId = "4.e@lz:lz-hawk", anchor = hawk - 1), env.ui!!.copy(stepsOpen = true, constantsOpen = true, pack = null))
        // The step itself went: the presenter stays at its place, on the step now there, which is followed from then on.
        env.door.mutableState.value = BriefFixtures.state(model = without("3", "4.e@lz:lz-hawk"))
        runCurrent()
        val there = env.shown!!.current!!.id
        assertEquals(ids[hawk + 1], there)
        assertEquals(there, env.ui!!.stepId)
        assertEquals(hawk - 1, env.ui!!.anchor)
        // The brief as it was: the step followed is found where it is.
        env.door.mutableState.value = BriefFixtures.state()
        runCurrent()
        assertEquals(there, env.shown!!.current!!.id)
        assertEquals(hawk + 1, env.shown!!.index)
    }

    @Test
    fun `nothing is shown or stepped to while the brief is read`() = vmTest { env ->
        env.door.mutableState.value = null
        env.model.present(narrow = false)
        runCurrent()
        val reading = env.shown!!
        assertTrue(reading.loading)
        assertEquals(-1, reading.index)
        assertNull(reading.view)
        assertTrue(reading.scene.isEmpty)
        env.model.nextStep()
        runCurrent()
        assertNull(env.ui!!.stepId)
        env.door.mutableState.value = BriefFixtures.state()
        runCurrent()
        assertEquals(0, env.shown!!.index)
        assertEquals("1", env.ui!!.stepId)
    }

    @Test
    fun `the bar names the open pack as the web's does, before its brief is read and after a rename`() = vmTest { env ->
        env.door.mutableState.value = null
        env.model.workspaceIs("p-1")
        env.model.present(narrow = false)
        runCurrent()
        assertEquals("The pack", env.shown!!.title)                     // no name heard yet: the web's `?? "The pack"`
        env.model.workspaceIs("p-1", "OP DOGWOOD")
        runCurrent()
        assertTrue(env.shown!!.loading)
        assertEquals("OP DOGWOOD", env.shown!!.title)                   // the pack's name, though its brief is still being read
        env.model.workspaceIs("p-1", "OP DOGWOOD 2")                    // renamed while it runs: it goes on, under the new name
        runCurrent()
        assertEquals("OP DOGWOOD 2", env.shown!!.title)
        env.door.mutableState.value = BriefFixtures.state()             // put together for "OP DK", before the rename reached it
        runCurrent()
        assertEquals("OP DOGWOOD 2 · Air movement brief", env.shown!!.title)
        assertEquals(listOf("open"), env.door.log)
    }

    @Test
    fun `a sandtable begun once the pack's name is heard names it at once`() = vmTest { env ->
        env.door.mutableState.value = null
        env.model.workspaceIs("p-1", "OP DOGWOOD")
        env.model.present(narrow = false)
        runCurrent()
        assertEquals("OP DOGWOOD", env.shown!!.title)
    }

    @Test
    fun `a brief that could not be put together says so, with no step`() = vmTest { env ->
        env.door.mutableState.value = null
        env.door.mutableFailed.value = true
        env.model.present(narrow = false)
        runCurrent()
        val shown = env.shown!!
        assertTrue(shown.failed)
        assertFalse(shown.loading)
        assertEquals(-1, shown.index)
    }

    @Test
    fun `ending writes what waits and closes the brief`() = vmTest { env ->
        env.model.present(narrow = false)
        runCurrent()
        env.model.endPresenting()
        runCurrent()
        assertNull(env.ui)
        assertNull(env.shown)
        assertEquals(listOf("open", "close"), env.door.log)
        // Ending again does nothing.
        env.model.endPresenting()
        runCurrent()
        assertEquals(listOf("open", "close"), env.door.log)
    }

    @Test
    fun `a sandtable ends with the workspace it was begun in`() = vmTest { env ->
        env.model.workspaceIs("p-1")
        env.model.present(narrow = false)
        runCurrent()
        env.model.workspaceIs("p-1")
        runCurrent()
        assertNotNull(env.ui)
        env.model.workspaceIs(null)
        runCurrent()
        assertNull(env.ui)
        assertEquals(listOf("open", "close"), env.door.log)
    }

    @Test
    fun `a sandtable begun outside a pack ends when one opens`() = vmTest(pack = null) { env ->
        env.model.workspaceIs(null)
        env.model.present(narrow = false)
        runCurrent()
        assertNull(env.ui!!.pack)
        assertEquals("This session · Air movement brief", env.shown!!.title)
        env.model.workspaceIs("p-2")
        runCurrent()
        assertNull(env.ui)
    }

    @Test
    fun `another account signing in ends it, and closing (the feature off, a phone's window) ends it`() = vmTest { env ->
        env.model.present(narrow = false)
        runCurrent()
        env.door.mutableOpened.value = false
        runCurrent()
        assertNull(env.ui)
        env.model.present(narrow = false)
        runCurrent()
        env.model.close()
        runCurrent()
        assertNull(env.ui)
        assertEquals("close", env.door.log.last())
    }

    @Test
    fun `a narrow window starts with the steps and the constants folded, and either opens again`() = vmTest { env ->
        env.model.present(narrow = true)
        runCurrent()
        assertFalse(env.ui!!.stepsOpen)
        assertFalse(env.ui!!.constantsOpen)
        env.model.showSteps(true)
        env.model.showConstants(true)
        runCurrent()
        assertTrue(env.ui!!.stepsOpen)
        assertTrue(env.ui!!.constantsOpen)
        // Neither does anything once it has ended.
        env.model.endPresenting()
        env.model.showSteps(false)
        runCurrent()
        assertNull(env.ui)
    }

    @Test
    fun `the setup opened again starts afresh, the sandtable over`() = vmTest { env ->
        env.model.present(narrow = false)
        runCurrent()
        env.model.goToStep(4)
        runCurrent()
        env.model.endPresenting()
        env.model.open()
        runCurrent()
        assertTrue(env.model.state.value.open)
        assertNull(env.ui)
        env.model.present(narrow = false)
        runCurrent()
        assertEquals(0, env.shown!!.index)
    }

    @Test
    fun `an opening asked while a close writes is dropped when a close comes after it`() = vmTest { env ->
        env.model.present(narrow = false)
        runCurrent()
        env.model.endPresenting()                                       // its close writes what waits...
        env.model.open()                                                // ...and the setup asked meanwhile waits for it
        env.model.close()                                               // then the window became a phone's
        runCurrent()
        assertFalse(env.model.state.value.open)
        assertNull(env.ui)
        assertFalse(env.door.mutableOpened.value)
        // One asked with no close after it opens once the close is over.
        env.model.present(narrow = false)
        runCurrent()
        env.model.endPresenting()
        env.model.open()
        runCurrent()
        assertTrue(env.model.state.value.open)
        assertTrue(env.door.mutableOpened.value)
    }

    @Test
    fun `a PDF that opens as the sandtable begins is let go, not left open`() = runTest(dispatcher) {
        val door = FakeDoor(BriefFixtures.state())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val opened = mutableListOf<FakePdf>()
        // The platform's renderer, which finishes opening whatever cancels the one who asked (it reads a file on its own thread).
        val pdfs = BriefPdfs { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await(); FakePdf(3).also { opened += it } } }
        val model = BriefViewModel(door, pdfs, object : BriefFileWriter { override suspend fun write(target: Uri, bytes: ByteArray) = Unit })
        runCurrent()
        model.open()
        model.show(SetupStep.REVIEW)
        advanceTimeBy(BriefViewModel.SETTLE_MS + 1)
        runCurrent()
        model.present(narrow = false)                                   // while the PDF is opening
        gate.complete(Unit)
        runCurrent()
        assertTrue("the PDF opened after it was let go is closed", opened.single().closed)
        assertNull(model.state.value.pdf.made)
    }

    @Test
    fun `what the map shows is the step's figure`() = vmTest { env ->
        env.model.present(narrow = false)
        runCurrent()
        env.model.goToStep(ids.indexOf("4.e@lz:lz-hawk"))
        runCurrent()
        val shown = env.shown!!
        assertEquals("stop", shown.view!!.kind)
        assertEquals(1, shown.scene.areas.size)
        assertTrue(shown.scene.marks.any { it is FigureMark.Arrow && it.fromTip && it.heading == 203.0 })
        assertEquals("LZ HAWK", shown.current!!.title)
        assertEquals("Flight lead", shown.step!!.who)
    }
}
