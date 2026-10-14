package app.ezpztac.data

import app.ezpztac.model.PointSet
import app.ezpztac.model.PointSets
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the import review adds below the screens: sets of points held for this session only ([SessionPointSets]) and how they join the saved ones
 * ([LocalPoints]); reading a file without saving it, and saving a set read earlier ([PointSetRepository.read], [PointSetRepository.save]); and the list of
 * threat files brought in, which goes with the picture ([ThreatFileLog]).
 */
class SessionImportsTest {
    private class Memory : PointSetViewStore {
        override fun load() = emptyMap<String, PointSetView>()

        override fun save(views: Map<String, PointSetView>) {}
    }

    private class Rig {
        val device = Device("A", FakeServer())
        val scheduler = RecordingScheduler()
        val repository = PointSetRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val views = PointSetViews(Memory())
        val session = SessionPointSets()
        val local = LocalPoints(repository, views, session)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    private val lps = Fixtures.bytes("sqlite/local-points.lps")

    private fun set(id: String, name: String = id.uppercase()) = PointSet(id = id, name = name, points = emptyList())

    // -- Held for this session ---------------------------------------------------------------------------------------

    @Test
    fun `a session holds sets in the order they came, up to its limit`() {
        val session = SessionPointSets()
        repeat(SessionPointSets.MAX) { assertTrue(session.add(set("s$it"))) }
        assertFalse(session.add(set("one-more")))
        assertEquals((0 until SessionPointSets.MAX).map { "s$it" }, session.sets.value.map { it.id })
        assertTrue(session.holds("s0"))
        assertFalse(session.holds("one-more"))
    }

    @Test
    fun `a set held is renamed, trimmed, and a blank name or an unknown set changes nothing`() {
        val session = SessionPointSets()
        session.add(set("a", "TAXI"))
        assertTrue(session.rename("a", "  NIGHT TAXI "))
        assertEquals("NIGHT TAXI", session.sets.value.single().name)
        assertFalse(session.rename("a", "   "))
        assertFalse(session.rename("b", "OTHER"))
        assertEquals("NIGHT TAXI", session.sets.value.single().name)
    }

    @Test
    fun `a set let go is said, one not held is nothing, and a sign-out lets go of all`() {
        val session = SessionPointSets()
        session.add(set("a"))
        session.add(set("b"))
        assertEquals("A", session.remove("a")?.name)
        assertNull(session.remove("a"))
        assertEquals(listOf("b"), session.sets.value.map { it.id })
        session.clear()
        assertTrue(session.sets.value.isEmpty())
    }

    // -- Joined with the saved sets ---------------------------------------------------------------------------------

    @Test
    fun `the session's sets come after the saved ones, marked, each in a colour of its own`() = rig {
        repository.import(lps, "ONE.lps")
        session.add((repository.read(lps, "TAXI.lps") as ImportOutcome.Imported).set)
        repository.import(lps, "TWO.lps")
        val sets = local.sets.first()
        assertEquals(listOf("ONE", "TWO", "TAXI"), sets.map { it.set.name })
        assertEquals(listOf(false, false, true), sets.map { it.inSession })
        assertEquals(RouteColors.PALETTE.take(3), sets.map { it.color })
        assertEquals(9, sets.last().set.points.size)                                         // drawn and looked up like any set
    }

    @Test
    fun `a set saved from the session is listed once, as the saved one, keeping its colour`() = rig {
        val read = (repository.read(lps, "TAXI.lps") as ImportOutcome.Imported).set
        session.add(read)
        val colour = local.sets.first().single().color
        repository.save(read)                                                                // for a moment, both lists have it
        val both = local.sets.first()
        assertEquals(listOf(read.id), both.map { it.set.id })
        assertFalse(both.single().inSession)
        assertEquals(colour, both.single().color)
    }

    // -- Reading and saving -------------------------------------------------------------------------------------------------

    @Test
    fun `reading a file makes a set without saving or sending anything`() = rig {
        val read = repository.read(lps, "NORTH GA.LPS") as ImportOutcome.Imported
        assertEquals("NORTH GA", read.set.name)
        assertEquals(9, read.set.points.size)
        assertTrue(read.set.points.all { it.id.matches(Regex("""lps-\d+-[0-9a-f]{6}""")) })
        assertTrue(device.outbox().isEmpty())
        assertEquals(0, scheduler.requested)
        assertTrue(repository.observeSets().first().isEmpty())
    }

    @Test
    fun `a name given is the set's, trimmed, a blank one is the file's, and a file with none is still called something`() = rig {
        assertEquals("KGVL", (repository.read(lps, "NORTH GA.LPS", "  KGVL ") as ImportOutcome.Imported).set.name)
        assertEquals("NORTH GA", (repository.read(lps, "NORTH GA.LPS", "   ") as ImportOutcome.Imported).set.name)
        assertEquals("LOCAL POINTS", (repository.read(lps, ".lps") as ImportOutcome.Imported).set.name)
        assertEquals("KGVL", (repository.import(lps, "NORTH GA.LPS", "KGVL") as ImportOutcome.Imported).set.name)
    }

    @Test
    fun `a file that is not local points is refused when read, as when imported`() = rig {
        assertEquals(
            ImportOutcome.Refused("This doesn't look like an .LPS local points file."),
            repository.read("not a database".toByteArray(), "junk.lps"),
        )
    }

    @Test
    fun `saving a set read earlier keeps its id and its points, and queues it for the server`() = rig {
        val read = (repository.read(lps, "TAXI.lps") as ImportOutcome.Imported).set
        val saved = repository.save(read)
        assertEquals(read.id, saved.id)
        val entry = device.outbox().single()
        assertEquals(Operation.CREATE, entry.operation)
        assertEquals(read.id, entry.uuid)
        assertEquals(1, scheduler.requested)
        val record = device.record(RecordKind.POINT_SET, read.id)!!
        assertEquals("TAXI", record.name)
        assertEquals(read.points, PointSets.parse(record.uuid, record.serverId, record.name, record.data).points)
    }

    @Test
    fun `a set saved with a blank name is called something, because the server refuses one with none`() = rig {
        val saved = repository.save(set("x", "  "))
        assertEquals("LOCAL POINTS", saved.name)
    }

    // -- Threat files --------------------------------------------------------------------------------------------------------

    private class NoVault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) {}
        override fun wipe() {}
    }

    private fun threat() = Threat("SA-6", "SHGPEWRR------", 34.5, -84.5, "", "SOF", radars = Radars.defaultPair())

    @Test
    fun `threat files are listed in the order they came, and a wipe of the picture clears the list`() = runTest {
        val threats = ThreatStore(NoVault(), backgroundScope, StandardTestDispatcher(testScheduler)) { 1_000L }
        val log = ThreatFileLog(threats)
        log.record("a.ths", 3)
        log.record("b.ths", 1)
        assertEquals(listOf(ThreatFileImport("a.ths", 3), ThreatFileImport("b.ths", 1)), log.files.first())
        threats.add(threat())
        threats.wipe()
        assertTrue(log.files.first().isEmpty())
        log.record("c.ths", 2)                                                               // brought in after the wipe: listed
        assertEquals(listOf(ThreatFileImport("c.ths", 2)), log.files.first())
    }
}
