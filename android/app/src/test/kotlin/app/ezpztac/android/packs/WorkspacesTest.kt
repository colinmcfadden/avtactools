package app.ezpztac.android.packs

import androidx.test.core.app.ApplicationProvider
import app.ezpztac.android.LastDiagram
import app.ezpztac.android.LastRouteSet
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.InMemoryOpenDiagramStore
import app.ezpztac.data.InMemoryOpenRouteSetStore
import app.ezpztac.data.OpenDiagrams
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSession
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SequentialIds
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncStore
import app.ezpztac.sync.SyncTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The workspace's two pieces on the device: the pack remembered for an account, and the person's own documents set aside while a pack is open. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspacesTest {
    @Test
    fun `the pack remembered is the account's own, and is forgotten with the Library`() {
        val prefs = LastPackPreferences(ApplicationProvider.getApplicationContext())
        assertNull(prefs.id(7))
        prefs.remember(7, "p-dk")
        assertEquals("p-dk", prefs.id(7))
        assertNull(prefs.id(8))                                                                   // another person on the device is never put in it
        prefs.remember(8, "p-ex")
        assertNull(prefs.id(7))                                                                   // one slot: the last person's
        assertEquals("p-ex", LastPackPreferences(ApplicationProvider.getApplicationContext()).id(8))
        prefs.remember(8, null)
        assertNull(prefs.id(8))
    }

    /** A store whose writes can fail, as a full disk does. */
    private class Flaky(private val inner: InMemorySyncStore = InMemorySyncStore()) : SyncStore, RecordFeed by inner {
        var failing = false
        override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T {
            if (failing) error("the disk is full")
            return inner.transaction(block)
        }
    }

    private class Remembered(var stored: String? = null) : LastDiagram, LastRouteSet {
        override fun id() = stored
        override fun remember(id: String) {
            stored = id
        }
    }

    private inner class Rig(scope: TestScope) {
        val store = Flaky()
        val sync = SyncRepository(store, SequentialIds("t"))
        val scheduler = RecordingScheduler()
        val diagrams = DiagramRepository(sync, store, scheduler)
        val routes = RouteRepository(sync, store, scheduler)
        val diagramSession = DiagramSession(diagrams, scope.backgroundScope)
        val routeSession = RouteSession(routes, scope.backgroundScope)
        val lastDiagram = Remembered()
        val lastSet = Remembered()
        val openDiagrams = OpenDiagrams(InMemoryOpenDiagramStore())
        val openSets = OpenRouteSets(InMemoryOpenRouteSetStore())
        val parking = SessionParking(diagramSession, routeSession, lastDiagram, lastSet, openDiagrams, openSets)
    }

    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")

    @Test
    fun `the person's own LZ_PZ and routes are written and set aside, and come back on the way out`() = runTest {
        val r = Rig(this)
        val lz = r.diagrams.create(target, "LZ HAWK")
        val set = r.routes.create("MISSION 1")
        r.diagramSession.open(lz.id)
        r.routeSession.open(set.id)
        r.lastDiagram.stored = lz.id
        r.lastSet.stored = set.id
        r.openDiagrams.add(lz.id)
        r.openSets.add(set.id)
        r.diagramSession.edit("Rename") { DiagramOps.setName(it, "LZ EAGLE") }                    // inside the pause before a write
        r.parking.park()
        assertNull(r.diagramSession.active.value)
        assertNull(r.routeSession.active.value)
        assertEquals("LZ EAGLE", r.diagrams.open(lz.id)?.name)                                    // written before it left
        r.parking.unpark()
        assertEquals(lz.id, r.diagramSession.active.value?.id)
        assertEquals(set.id, r.routeSession.active.value?.id)
    }

    @Test
    fun `a change that cannot be written keeps both documents open, and owed`() = runTest {
        val r = Rig(this)
        val lz = r.diagrams.create(target, "LZ HAWK")
        val set = r.routes.create("MISSION 1")
        r.diagramSession.open(lz.id)
        r.routeSession.open(set.id)
        r.diagramSession.edit("Rename") { DiagramOps.setName(it, "LZ EAGLE") }
        r.store.failing = true
        try {
            r.parking.park()
            fail("parking should have said the change could not be written")
        } catch (_: IllegalStateException) {
        }
        assertEquals("LZ EAGLE", r.diagramSession.active.value?.name)                             // still open, with the change
        assertEquals(set.id, r.routeSession.active.value?.id)                                    // and the routes were not set aside without it
        assertTrue(r.diagramSession.saveFailed.value)
        r.store.failing = false
        r.parking.park()
        assertEquals("LZ EAGLE", r.diagrams.open(lz.id)?.name)
    }

    @Test
    fun `one that will not come back does not keep the other, and one closed since stays closed`() = runTest {
        val r = Rig(this)
        val set = r.routes.create("MISSION 1")
        r.lastDiagram.stored = "gone"                                                             // deleted elsewhere meanwhile
        r.openDiagrams.add("gone")
        r.lastSet.stored = set.id
        r.openSets.add(set.id)
        r.parking.unpark()
        assertNull(r.diagramSession.active.value)
        assertEquals(set.id, r.routeSession.active.value?.id)
        r.routeSession.close()
        r.openSets.remove(set.id)                                                                 // closed from the panel while away
        r.parking.unpark()
        assertNull(r.routeSession.active.value)
    }
}
