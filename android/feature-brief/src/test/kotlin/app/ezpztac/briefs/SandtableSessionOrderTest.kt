package app.ezpztac.briefs

import android.net.Uri
import app.ezpztac.data.BriefState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The setup and the sandtable sharing one brief, over a door that behaves as core-data's `BriefSession` does (opening one already open does nothing; closing shuts it at
 * once and then writes what waits, which takes a moment), on a main thread that runs work as soon as it is asked, as `Dispatchers.Main.immediate` does (AGENTS.md
 * §14: a queued test dispatcher hides what the main thread does).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SandtableSessionOrderTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    /** `BriefSession` as the screens see it: the brief while open, nothing while not; a close that writes for as long as [writing] holds it. */
    private class SessionLikeDoor(private val brief: BriefState) : BriefDoor {
        private val active = MutableStateFlow(false)
        private val compiled = MutableStateFlow<BriefState?>(null)
        override val state: StateFlow<BriefState?> = compiled
        override val opened: StateFlow<Boolean> = active
        override val failed: StateFlow<Boolean> = MutableStateFlow(false)
        override val refusals = MutableSharedFlow<String>()
        var writing: CompletableDeferred<Unit>? = null
        val log = mutableListOf<String>()

        override fun open() {
            if (active.value) return
            log += "open"
            active.value = true
            compiled.value = brief
        }

        override suspend fun close() {
            log += "close"
            active.value = false
            compiled.value = null
            writing?.await()
        }

        override fun change(transform: (JsonObject) -> JsonObject) = Unit

        override fun refreshWeather() = Unit

        override suspend fun print(model: JsonObject): ByteArray = byteArrayOf(0x25)
    }

    private fun model(door: BriefDoor) = BriefViewModel(door, FakePdfs(), object : BriefFileWriter {
        override suspend fun write(target: Uri, bytes: ByteArray) = Unit
    })

    @Test
    fun `presenting from the setup keeps the one brief open, and ending the sandtable closes it`() = runTest(dispatcher) {
        val door = SessionLikeDoor(BriefFixtures.state())
        val vm = model(door)
        vm.open()
        vm.present(narrow = false)
        assertFalse(vm.state.value.open)
        assertEquals(0, vm.sandtable.value!!.index)
        assertEquals(listOf("open"), door.log)                          // the brief the setup opened is the one presented
        vm.endPresenting()
        assertEquals(listOf("open", "close"), door.log)
        assertFalse(door.opened.value)
        assertNull(vm.sandtable.value)
    }

    @Test
    fun `the setup asked for as the sandtable's close writes opens the brief once it has written`() = runTest(dispatcher) {
        val door = SessionLikeDoor(BriefFixtures.state())
        val vm = model(door)
        vm.present(narrow = false)
        door.writing = CompletableDeferred()
        vm.endPresenting()
        vm.open()
        assertFalse("not opened under the close", vm.state.value.open)
        door.writing!!.complete(Unit)
        assertTrue(vm.state.value.open)
        assertTrue(door.opened.value)
        assertEquals(listOf("open", "close", "open"), door.log)
    }

    @Test
    fun `an opening that waits for a close is dropped by a close after it`() = runTest(dispatcher) {
        val door = SessionLikeDoor(BriefFixtures.state())
        val vm = model(door)
        vm.present(narrow = false)
        door.writing = CompletableDeferred()
        vm.endPresenting()
        vm.present(narrow = false)                                      // asked again as the close writes...
        vm.close()                                                      // ...then the feature went
        door.writing!!.complete(Unit)
        assertNull(vm.sandtable.value)
        assertFalse(door.opened.value)
        assertEquals(listOf("open", "close"), door.log)
    }

    @Test
    fun `the brief closing itself for another account ends the sandtable with it`() = runTest(dispatcher) {
        val door = SessionLikeDoor(BriefFixtures.state())
        val vm = model(door)
        vm.present(narrow = false)
        vm.goToStep(5)
        assertEquals(5, vm.sandtable.value!!.index)
        door.close()                                                    // as BriefSession does when the account changes
        assertNull(vm.sandtable.value)
        assertNull(vm.state.value.presenting)
    }
}
