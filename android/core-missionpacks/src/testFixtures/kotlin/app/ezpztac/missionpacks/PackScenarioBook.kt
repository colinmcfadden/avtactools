package app.ezpztac.missionpacks

import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.LatLon
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RouteSet
import app.ezpztac.model.Wind
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * A server (the fake, or the real one) and the people and devices that use it. What the owner does outside the operation stream (makes
 * the pack, adds and removes people, finishes it) goes through the server's own routes, as the screens will; what a device does goes
 * through its [PackEngine].
 */
public interface PackEnv : AutoCloseable {
    /** A new account with Mission Packs on, called [name]. */
    public suspend fun person(name: String): PackPerson

    /** A new device signed in as [person], with a copy of its own and a library of its own. */
    public suspend fun device(label: String, person: PackPerson): PackDevice

    /** A new pack owned by [owner]: its uuid. */
    public suspend fun createPack(owner: PackPerson, name: String): String

    /** [member] in [pack] as [role] ("editor" or "viewer"), let in by its [owner] (on the real server: invited by email, the link accepted). */
    public suspend fun addMember(pack: String, owner: PackPerson, member: PackPerson, role: String)

    public suspend fun setRole(pack: String, owner: PackPerson, member: PackPerson, role: String)

    public suspend fun removeMember(pack: String, owner: PackPerson, member: PackPerson)

    public suspend fun finish(pack: String, owner: PackPerson)

    /** [ops], made by [by] on a device the scenario does not follow, sent at once. */
    public suspend fun send(pack: String, by: PackPerson, vararg ops: JsonObject)

    /** The pack's log, oldest first, as [reader] reads it. */
    public suspend fun log(pack: String, reader: PackPerson): List<JsonObject>

    /** Item [item]'s data as the server holds it, read by [reader]; null when the pack has no such item. */
    public suspend fun data(pack: String, reader: PackPerson, item: String): JsonElement?

    /** One page of the log is [size] events from now on. Only the fake can be told ([PackScenario.fakeOnly]). */
    public fun pageSize(size: Int)

    /** An item may grow to [bytes] of JSON at most (the real server's limit is 5 MB). Only the fake can be told. */
    public fun limitItemSize(bytes: Int)
}

/** One rule of how packs behave, as a story between devices. [fakeOnly] ones need what only the fake server can be told. */
public class PackScenario(public val name: String, public val fakeOnly: Boolean = false, public val run: suspend (PackEnv) -> Unit) {
    override fun toString(): String = name
}

/**
 * How mission packs behave between people and devices, as scenarios. They are plain suspend functions, not tests, so any test framework
 * can run them, in real time: JUnit 5 against the fake server and the real one (here), JUnit 4 under Robolectric with each device's copy
 * in Room (core-data). Running the same stories against the fake and the real server is what keeps the fake from drifting from what the
 * server does, and running them through each store is what shows the stores agree.
 *
 * Each starts from OP DK: Colin's pack, Sam an editor in it, and LZ HAWK (landing heading 270) made by Colin, open on Colin's device A
 * and Sam's device B; the route scenario has the set ROUTES 1 in it instead, open in an editor on each device ([PackRouteEditor]). Neither
 * device has a live stream (the real one here has none either): each asks for what is new every so often.
 */
public object PackScenarioBook {
    /** Fast enough that a story takes a second or two, against the fake or the real server. */
    public val TIMING: PackTiming = PackTiming(pollMs = 100, presenceMs = 10, retryMs = listOf(50, 100, 200), eventsPage = 500)

    public val all: List<PackScenario> = listOf(
        PackScenario("two people change different fields of one LZ/PZ at once: both stand, on the server and on both devices") { env ->
            val s = opDk(env)
            s.b.network.offline = true                                  // Sam's waits on his device, so the two cross
            assertNull(s.a.edit(setHeading(90)))
            assertNull(s.b.edit(setNotes("dusty")))
            assertEquals(90, s.a.heading())                             // each shown at once, where it was made
            assertEquals("dusty", s.b.notes())
            s.a.sent(s.pack)
            s.b.online()
            s.b.sent(s.pack)
            val data = env.data(s.pack, s.colin, "lz-1")
            assertEquals(90, heading(data))
            assertEquals("dusty", notes(data))
            eventually("both devices show both changes") { listOf(s.a, s.b).all { it.heading() == 90 && it.notes() == "dusty" } }
        },
        PackScenario("two people change the same field: the change that reaches the pack later stands, everywhere") { env ->
            val s = opDk(env)
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(180)))                       // made first, but it reaches the pack second
            assertNull(s.a.edit(setHeading(90)))
            s.a.sent(s.pack)
            s.b.online()
            s.b.sent(s.pack)
            assertEquals(180, s.serverHeading())
            eventually("both devices show the later one") { s.a.heading() == 180 && s.b.heading() == 180 }
        },
        PackScenario("a pack finished while a device was offline: what it made meanwhile is dropped, shown back as the pack's, and kept as NAME (my edits) once the pack is closed") { env ->
            val s = opDk(env)
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(45)))
            assertEquals(45, s.b.heading())
            env.finish(s.pack, s.colin)
            s.b.online()
            eventually("Sam's device hears the pack is finished") { s.b.state?.readOnly == true && s.b.stored(s.pack)?.dropped?.size == 1 }
            assertEquals(listOf("pack_finished"), s.b.stored(s.pack)!!.dropped.map { it.reason })
            assertTrue(s.b.stored(s.pack)!!.pending.isEmpty())
            assertEquals(270, s.serverHeading())                        // nothing of it reached the pack
            eventually("the pack's version is shown") { s.b.heading() == 270 }
            assertEquals("read_only", s.b.edit(setHeading(50)))
            eventually("Sam's device is told") { s.b.notices.any { it is PackNotice.Dropped && it.reasons == setOf("pack_finished") } }
            assertTrue(s.b.keeper.records.isEmpty())                    // open: they wait for the person
            s.b.close()
            eventually("kept in Sam's library once the pack is closed") { s.b.keeper.records.isNotEmpty() && s.b.stored(s.pack)?.dropped?.isEmpty() == true }
            val kept = s.b.keeper.records.single().version
            assertEquals("LZ HAWK (my edits)", kept.name)
            assertEquals(45, heading(kept.data))
            // Told once it is on the device and in the library, which a check of those can see first.
            eventually("Sam's device is told, and the library's new record is sent up") {
                s.b.notices.any { it is PackNotice.Kept && it.packName == "OP DK" } && s.b.backgroundSyncs > 0
            }
        },
        PackScenario("someone made a viewer: their edits are refused, those they made offline are kept, and nothing reaches the pack") { env ->
            val s = opDk(env)
            env.setRole(s.pack, s.colin, s.sam, "viewer")
            eventually("Sam's device hears he may only view") { s.b.state?.readOnly == true }
            assertEquals("read_only", s.b.edit(setHeading(10)))

            env.setRole(s.pack, s.colin, s.sam, "editor")
            eventually("and that he may edit again") { s.b.state?.readOnly == false }
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(15)))
            env.setRole(s.pack, s.colin, s.sam, "viewer")
            s.b.online()
            eventually("what he made offline is refused") { s.b.stored(s.pack)?.dropped?.map { it.reason } == listOf("read_only") }
            assertEquals(270, s.serverHeading())
            s.b.close()
            eventually("and kept in his library") { s.b.keeper.records.size == 1 }
            assertEquals(15, heading(s.b.keeper.records.single().version.data))
        },
        PackScenario("a brief is open to everyone in every state of the pack: a viewer's and a finished pack's brief edits reach it, and nothing else does") { env ->
            // docs/MISSION_BRIEF.md §3: the server takes a batch whose every operation is on a brief in a read-only pack, and the device sends them alone.
            val s = opDk(env)
            env.send(s.pack, s.colin, json("""{"type": "item.create", "item": "br-1", "kind": "brief", "name": "OP DK BRIEF", "data": {"version": 1, "answers": {}}}"""))
            eventually("both devices have the brief") { s.a.item("br-1") != null && s.b.item("br-1") != null }
            // The server's own words for a brief made with no sentence of its client's.
            assertTrue(env.log(s.pack, s.colin).any { (it["summary"] as? JsonPrimitive)?.contentOrNull == "Colin P. added the brief \"OP DK BRIEF\"." })

            env.setRole(s.pack, s.colin, s.sam, "viewer")
            eventually("Sam's device hears he may only view") { s.b.state?.readOnly == true }
            assertNull(s.b.edit(setAnswer("2.b", "500-1 day")))
            assertEquals("read_only", s.b.edit(setHeading(10)))
            assertEquals("read_only", s.b.edit(setAnswer("2.d", "11:15"), setHeading(10)))   // one edit with anything else in it is not a brief's
            s.b.sent(s.pack)
            assertEquals("500-1 day", answer(env.data(s.pack, s.colin, "br-1"), "2.b"))

            // Waiting on Sam's device when the pack is finished: the brief edit still goes, the LZ/PZ's is refused and kept as his own.
            env.setRole(s.pack, s.colin, s.sam, "editor")
            eventually("and that he may edit again") { s.b.state?.readOnly == false }
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(45)))
            assertNull(s.b.edit(setAnswer("3.a", "Insert the teams.")))
            env.finish(s.pack, s.colin)
            s.b.online()
            eventually("the brief edit reaches the finished pack") { answer(env.data(s.pack, s.colin, "br-1"), "3.a") == "Insert the teams." }
            eventually("and the other is refused") { s.b.stored(s.pack)?.let { it.pending.isEmpty() && it.dropped.map { d -> d.reason } == listOf("pack_finished") } == true }
            assertEquals(270, s.serverHeading())
            assertNull(s.a.edit(setAnswer("5.a.iii", "standard")))                            // the owner too, in the pack he finished
            s.a.sent(s.pack)
            assertEquals("standard", answer(env.data(s.pack, s.colin, "br-1"), "5.a.iii"))
            eventually("everyone sees every answer") { listOf(s.a, s.b).all { answer(it.item("br-1")?.data, "5.a.iii") == "standard" } }
        },
        PackScenario("someone removed from the pack: it is gone for them, what they had waiting is kept, and the device forgets the pack") { env ->
            val s = opDk(env)
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(33)))
            env.removeMember(s.pack, s.colin, s.sam)
            s.b.online()
            eventually("the pack is gone for Sam") { s.b.state?.status == PackStatus.GONE }
            eventually("what he had waiting is kept, and the device forgets the pack") { s.b.keeper.records.size == 1 && s.b.stored(s.pack) == null }
            val kept = s.b.keeper.records.single().version
            assertEquals("LZ HAWK (my edits)", kept.name)
            assertEquals(33, heading(kept.data))
            eventually("Sam's device is told the pack is gone") { s.b.notices.any { it is PackNotice.Gone } }
            assertEquals("gone", s.b.edit(setHeading(34)))
            assertEquals(270, s.serverHeading())
        },
        PackScenario("an answer lost after the pack took the batch: the batch goes again, unchanged, and is applied once") { env ->
            val s = opDk(env)
            s.b.network.loseNextAnswers(1)
            assertNull(s.b.edit(setHeading(120)))
            val id = s.b.opIds.last()
            s.b.sent(s.pack)
            assertEquals(1, s.logged(id))
            assertEquals(120, s.serverHeading())
            eventually("Colin sees it") { s.a.heading() == 120 }
        },
        PackScenario("a batch out when the app was ended: the pack's events confirm it when the app starts again, and it is never applied twice") { env ->
            val s = opDk(env)
            // In the back, as an app about to be ended is: it no longer follows the pack, so no poll hears the pack take the batch, and confirms
            // it, before the app is ended with it out (which would leave nothing for the start to find).
            s.b.engine.foreground(false)
            val answer = s.b.network.holdNextAnswer()
            assertNull(s.b.edit(setHeading(77)))
            val id = s.b.opIds.last()
            eventually("the pack took it") { s.serverHeading() == 77 }
            assertEquals(listOf(PendingState.SENT), s.b.stored(s.pack)!!.pending.map { it.state })  // written as sent before it went
            s.b.restart()                                               // the app ended with the batch out, and started again
            answer.complete(Unit)                                       // the old answer reaches nobody
            s.b.sent(s.pack)
            eventually("the pack is followed again") { s.b.state?.status == PackStatus.POLLING && s.b.heading() == 77 }
            assertEquals(1, s.logged(id))
        },
        PackScenario("a long time offline, across restarts: the device's copy is shown and edited, and everything goes once, in order, when the connection is back") { env ->
            val s = opDk(env)
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(10)))
            assertNull(s.b.edit(setNotes("wet")))
            s.b.restart()
            eventually("the device's copy is shown with no signal") { s.b.state?.status == PackStatus.OFFLINE }
            assertEquals(10, s.b.heading())
            assertNull(s.b.edit(setHeading(30)))
            // A background sync with no signal leaves it all, and ends at once rather than spend its minute waiting for a way through.
            assertEquals(DrainOutcome.RETRY, withTimeout(10_000) { s.b.drainInBackground(timeoutMs = 60_000) })
            assertEquals(3, s.b.stored(s.pack)!!.pending.size)
            s.b.network.offline = false
            assertEquals(DrainOutcome.DONE, s.b.drainInBackground(timeoutMs = 20_000))
            assertTrue(s.b.stored(s.pack)!!.pending.isEmpty())
            val logged = env.log(s.pack, s.colin).mapNotNull { (it["client_op_id"] as? JsonPrimitive)?.contentOrNull }
            assertEquals(s.b.opIds, logged.filter { it in s.b.opIds })   // each once, in the order made
            val data = env.data(s.pack, s.colin, "lz-1")
            assertEquals(30, heading(data))
            assertEquals("wet", notes(data))
            s.b.enable()
            s.b.open(s.pack)
            eventually("opened again, in step") { s.b.state?.status == PackStatus.POLLING && s.b.heading() == 30 && s.b.notes() == "wet" }
        },
        PackScenario("a background sync after the app was ended keeps in the library what a pack refused meanwhile") { env ->
            val s = opDk(env)
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(61)))
            s.b.quit()
            env.finish(s.pack, s.colin)
            s.b.network.offline = false
            assertEquals(DrainOutcome.DONE, s.b.drainInBackground(timeoutMs = 20_000))
            val kept = s.b.keeper.records.single().version
            assertEquals("LZ HAWK (my edits)", kept.name)
            assertEquals(61, heading(kept.data))
            val left = s.b.stored(s.pack)!!
            assertTrue(left.pending.isEmpty() && left.dropped.isEmpty())
            assertEquals(270, s.serverHeading())
        },
        PackScenario("an edit to an item someone deleted meanwhile changes nothing, and the device says so") { env ->
            val s = opDk(env)
            s.b.network.offline = true
            assertNull(s.b.edit(setHeading(5)))
            env.send(s.pack, s.colin, json("""{"type": "item.delete", "item": "lz-1"}"""))
            s.b.online()
            s.b.sent(s.pack)
            eventually("said to have changed nothing") { s.b.notices.any { it is PackNotice.OwnSkipped } }
            eventually("and the item is gone") { s.b.item("lz-1") == null }
            assertNull(env.data(s.pack, s.colin, "lz-1"))
        },
        PackScenario("an edit that would make an item too large is refused alone, kept as NAME (my edits), and what is made after it goes", fakeOnly = true) { env ->
            val s = opDk(env)
            env.limitItemSize(400)
            assertNull(s.b.edit(setNotes("x".repeat(500))))
            eventually("the pack refuses it as too large") { s.b.stored(s.pack)?.dropped?.map { it.reason } == listOf("item_too_large") }
            eventually("Sam's device is told") { s.b.notices.any { it is PackNotice.Dropped && it.reasons == setOf("item_too_large") } }
            assertNull(s.b.edit(setHeading(15)))
            s.b.sent(s.pack)
            val data = env.data(s.pack, s.colin, "lz-1")
            assertEquals(15, heading(data))
            assertEquals("", notes(data))                               // the whole batch was rolled back
            s.b.close()
            eventually("kept in Sam's library") { s.b.keeper.records.size == 1 }
            assertEquals(500, notes(s.b.keeper.records.single().version.data)?.length)
        },
        PackScenario("a device that was in the back catches up across more than one page of the log", fakeOnly = true) { env ->
            val s = opDk(env)
            env.pageSize(3)
            s.b.engine.foreground(false)
            (1..8).forEach { env.send(s.pack, s.colin, setHeading(100 + it)) }
            s.b.engine.foreground(true)
            eventually("Sam's device has all eight") { s.b.heading() == 108 }
        },
        PackScenario("a route's checkpoint moved on one device reaches the other in place, and the pack holds the route once, at the later move") { env ->
            val colin = env.person("Colin P.")
            val sam = env.person("Sam B.")
            val pack = env.createPack(colin, "OP DK")
            env.addMember(pack, colin, sam, "editor")
            env.send(pack, colin, json("""{"type": "item.create", "item": "rt-1", "kind": "route", "name": "ROUTES 1", "data": $RED_1}"""))
            val a = env.device("A", colin)
            val b = env.device("B", sam)
            a.enable()
            a.open(pack)
            b.enable()
            b.open(pack)
            eventually("both devices have ROUTES 1") { a.item("rt-1") != null && b.item("rt-1") != null }
            val onA = PackRouteEditor(a, pack, "rt-1").apply { open() }
            val onB = PackRouteEditor(b, pack, "rt-1").apply { open() }

            // Colin nudges the RP north, as the app moves a point: the ground fetched for its old place goes with it, which the pack is sent as null.
            onA.change { movedRp(it, lat = 34.6045, lon = null) }
            assertNull(onA.look())
            a.sent(pack)
            assertNull(onA.look())                                     // in step: nothing more (an editor once sent the route again here)
            a.sent(pack)
            assertEquals(listOf(RED_1_ID), routeIds(env.data(pack, colin, "rt-1")), "the pack holds RED 1 once")
            eventually("Sam's editor has the RP where Colin put it") { onB.look() == null && onB.at(RED_1_ID, "rp") == LatLon(34.6045, -84.2) }
            assertEquals(LatLon(34.55, -84.15), onB.at(RED_1_ID, "cp1"))

            // Sam puts it at a grid while Colin, with no signal, nudges it east from where he had it: Colin's reaches the pack later, and stands whole.
            a.network.offline = true
            onB.change { movedRp(it, lat = 34.58, lon = -84.23) }
            assertNull(onB.look())
            b.sent(pack)
            onA.change { movedRp(it, lat = null, lon = -84.195) }
            assertNull(onA.look())
            a.online()
            a.sent(pack)
            val later = LatLon(34.6045, -84.195)
            eventually("both editors have the RP where Colin put it last") {
                onA.look() == null && onB.look() == null && onA.at(RED_1_ID, "rp") == later && onB.at(RED_1_ID, "rp") == later
            }
            val data = env.data(pack, colin, "rt-1")
            assertEquals(listOf(RED_1_ID), routeIds(data), "the pack still holds RED 1 once")
            val route = data!!.jsonObject.getValue("routes").jsonArray[0].jsonObject
            val rp = route.getValue("points").jsonArray.map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.contentOrNull == "rp" }
            assertEquals(later, LatLon(rp.getValue("lat").jsonPrimitive.double, rp.getValue("lon").jsonPrimitive.double))
            // The ground of the point moved is gone, the others' kept.
            assertEquals(listOf("cp1", "sp"), route.getValue("elevations").jsonObject.filterValues { it !is JsonNull }.keys.sorted())
            for (editor in listOf(onA, onB)) {
                assertEquals(LatLon(34.55, -84.15), editor.at(RED_1_ID, "cp1"))
                assertEquals(setOf("sp", "cp1"), editor.document?.routes?.single()?.elevations?.keys)
            }
        },
        PackScenario("an aircraft moved north on one device and east on the other stands whole where the later move put it, on both devices and in the pack") { env ->
            val colin = env.person("Colin P.")
            val sam = env.person("Sam B.")
            val pack = env.createPack(colin, "OP DK")
            env.addMember(pack, colin, sam, "editor")
            env.send(pack, colin, json("""{"type": "item.create", "item": "lz-1", "kind": "lz", "name": "LZ HAWK", "data": $HAWK}"""))
            val a = env.device("A", colin)
            val b = env.device("B", sam)
            a.enable()
            a.open(pack)
            b.enable()
            b.open(pack)
            eventually("both devices have LZ HAWK") { a.item("lz-1") != null && b.item("lz-1") != null }
            val onA = PackLzEditor(a, pack, "lz-1").apply { open() }
            val onB = PackLzEditor(b, pack, "lz-1").apply { open() }

            // Colin, with no signal, nudges Chalk 1 north (its latitude alone changes); Sam nudges it east, and his reaches the pack first.
            a.network.offline = true
            onA.change { moved(it, lat = 34.5009, lon = null) }
            assertNull(onA.look())
            onB.change { moved(it, lat = null, lon = -84.0988) }
            assertNull(onB.look())
            b.sent(pack)
            a.online()
            a.sent(pack)
            // Colin's move reached the pack later: it stands whole, his latitude and the longitude he had, never his latitude and Sam's longitude.
            val later = LatLon(34.5009, -84.1)
            eventually("both editors have Chalk 1 where Colin put it") {
                onA.look() == null && onB.look() == null && onA.at("helicopters", CHALK_1) == later && onB.at("helicopters", CHALK_1) == later
            }
            val chalk1 = env.data(pack, colin, "lz-1")!!.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray
                .single { it.jsonObject["id"] == CHALK_1 }.jsonObject
            assertEquals(later, LatLon(chalk1.getValue("lat").jsonPrimitive.double, chalk1.getValue("lon").jsonPrimitive.double))
            for (editor in listOf(onA, onB)) assertEquals(LatLon(34.501, -84.1), editor.at("helicopters", CHALK_2))
        },
        PackScenario("a checkpoint given its first altitude on one device and its first wind on the other has both, and its altitude stands whole as the later set it") { env ->
            val colin = env.person("Colin P.")
            val sam = env.person("Sam B.")
            val pack = env.createPack(colin, "OP DK")
            env.addMember(pack, colin, sam, "editor")
            env.send(pack, colin, json("""{"type": "item.create", "item": "rt-1", "kind": "route", "name": "ROUTES 1", "data": $RED_1}"""))
            val a = env.device("A", colin)
            val b = env.device("B", sam)
            a.enable()
            a.open(pack)
            b.enable()
            b.open(pack)
            eventually("both devices have ROUTES 1") { a.item("rt-1") != null && b.item("rt-1") != null }
            val onA = PackRouteEditor(a, pack, "rt-1").apply { open() }
            val onB = PackRouteEditor(b, pack, "rt-1").apply { open() }

            // The checkpoint has no values of its own: Colin, with no signal, gives it an altitude; Sam a wind, which reaches the pack first.
            a.network.offline = true
            onA.change { own(it, "cp1") { o -> o.copy(altitude = AltitudeSetting(500.0, "agl")) } }
            assertNull(onA.look())
            onB.change { own(it, "cp1") { o -> o.copy(wind = Wind(250.0, 18.0)) } }
            assertNull(onB.look())
            b.sent(pack)
            a.online()
            a.sent(pack)
            eventually("both editors have the checkpoint's altitude and its wind") {
                onA.look() == null && onB.look() == null && listOf(onA, onB).all { it.document?.route(RED_1_ID)?.plan?.perPoint?.get("cp1") == PointOverride(AltitudeSetting(500.0, "agl"), wind = Wind(250.0, 18.0)) }
            }
            val held = env.data(pack, colin, "rt-1")!!.jsonObject.getValue("routes").jsonArray[0].jsonObject.getValue("plan").jsonObject.getValue("perPoint").jsonObject.getValue("cp1").jsonObject
            assertEquals(setOf("altitude", "wind"), held.keys)

            // Then each changes that altitude in part, Colin its number and Sam what it is measured from: Sam's reaches the pack later, and stands whole.
            b.network.offline = true
            onA.change { own(it, "cp1") { o -> o.copy(altitude = AltitudeSetting(700.0, "agl")) } }
            assertNull(onA.look())
            onB.change { own(it, "cp1") { o -> o.copy(altitude = AltitudeSetting(500.0, "msl")) } }
            assertNull(onB.look())
            a.sent(pack)
            b.online()
            b.sent(pack)
            eventually("both editors have Sam's altitude, never Colin's number measured from the sea") {
                onA.look() == null && onB.look() == null && listOf(onA, onB).all { it.document?.route(RED_1_ID)?.plan?.perPoint?.get("cp1")?.altitude == AltitudeSetting(500.0, "msl") }
            }
        },
    )

    // [route] with point [pointId]'s own values changed by [change], as the route's plan changes them.
    private fun own(set: RouteSet, pointId: String, change: (PointOverride) -> PointOverride): RouteSet = set.mapRoute(RED_1_ID) { route ->
        route.copy(plan = route.plan.copy(perPoint = route.plan.perPoint + (pointId to change(route.plan.perPoint[pointId] ?: PointOverride()))))
    }

    // -- LZ HAWK ----------------------------------------------------------------------------------------------------------------

    private val CHALK_1 = JsonPrimitive(101)
    private val CHALK_2 = JsonPrimitive(102)

    // LZ HAWK as a client in step with today's web writes it: analysed, two aircraft.
    private val HAWK = """{"schemaVersion": 2, "status": "analyzed",
        "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"}, "mapData": {"mgrs": "16S GC 28864 55349"}, "flightData": {"landing_hdg": "090°"},
        "analysis": {"customLZ": null, "detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], "results": {"area": 1}, "gridElevation": "1200", "latLong": ""},
        "graphics": {"helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "rotation": 90, "type": "helo", "profileId": "uh60l"},
                                     {"id": 102, "lat": 34.501, "lon": -84.1, "rotation": 90, "type": "helo", "profileId": "uh60l"}],
                     "doghouses": [], "pzMarkers": [], "sectorsOfFire": [], "goArounds": [], "units": [], "measurements": [], "exportBox": null}}"""

    // HAWK's Chalk 1 moved as the inspector's nudge pad moves it ([lat] or [lon] null: not moved that way).
    private fun moved(diagram: Diagram, lat: Double?, lon: Double?): Diagram {
        val chalk1 = checkNotNull(DiagramOps.graphic(diagram, "helicopters", "101"))
        val patch = JsonObject(mapOf("lat" to (lat?.let(::JsonPrimitive) ?: chalk1.getValue("lat")), "lon" to (lon?.let(::JsonPrimitive) ?: chalk1.getValue("lon"))))
        return DiagramOps.patchGraphic(diagram, "helicopters", CHALK_1, patch)
    }

    // -- ROUTES 1 ---------------------------------------------------------------------------------------------------------------

    private const val RED_1_ID = "sketch-1760000000000-ab12cd34"

    // RED 1 as the web writes it into a pack: the SP, a checkpoint and the RP, the ground fetched for each.
    private val RED_1 = """{"version": 1, "routes": [{"id": "$RED_1_ID", "name": "RED 1", "color": "#FF453A",
        "plan": {"aircraft": "UH-60L Black Hawk", "aircraftProfile": "uh60l", "airspeed": {"value": 100, "type": "ground"}, "altitude": {"value": 50, "ref": "agl"},
                 "wind": {"dirTrue": 0, "speedKts": 0}, "tempC": 15, "fuelFlowLbHr": 960, "date": "", "perPoint": {}},
        "elevations": {"sp": 1000, "cp1": 1010, "rp": 1020},
        "points": [
          {"id": "sp", "lat": 34.5, "lon": -84.1, "ele": null, "role": "start", "kind": "amps", "ptType": "start", "name": ".SP"},
          {"id": "cp1", "lat": 34.55, "lon": -84.15, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "turn", "name": ".CP1"},
          {"id": "rp", "lat": 34.6, "lon": -84.2, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "ip", "name": ".RP"}]}]}"""

    // RED 1's RP moved as the app moves a point ([lat] or [lon] null: not moved that way), the ground fetched for its old place forgotten.
    private fun movedRp(set: RouteSet, lat: Double?, lon: Double?): RouteSet = set.mapRoute(RED_1_ID) { route ->
        route.copy(
            points = route.points.map { if (it.id == "rp") it.copy(lat = lat ?: it.lat, lon = lon ?: it.lon, chartElevationFt = null) else it },
            elevations = route.elevations - "rp",
        )
    }

    // The ids of the routes of a route set's data, in order.
    private fun routeIds(data: JsonElement?): List<String?> =
        ((data as? JsonObject)?.get("routes") as? JsonArray).orEmpty().map { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }

    // -- OP DK ----------------------------------------------------------------------------------------------------------------

    private class OpDk(val env: PackEnv, val colin: PackPerson, val sam: PackPerson, val pack: String, val a: PackDevice, val b: PackDevice) {
        suspend fun serverHeading(): Int? = heading(env.data(pack, colin, "lz-1"))

        /** How many times the pack's log has the edit sent as [id]. */
        suspend fun logged(id: String): Int = env.log(pack, colin).count { (it["client_op_id"] as? JsonPrimitive)?.contentOrNull == id }
    }

    private suspend fun opDk(env: PackEnv): OpDk {
        val colin = env.person("Colin P.")
        val sam = env.person("Sam B.")
        val pack = env.createPack(colin, "OP DK")
        env.addMember(pack, colin, sam, "editor")
        env.send(pack, colin, json("""{"type": "item.create", "item": "lz-1", "kind": "lz", "name": "LZ HAWK", "data": {"flightData": {"landingHeading": 270}, "notes": ""}}"""))
        val a = env.device("A", colin)
        val b = env.device("B", sam)
        a.enable()
        a.open(pack)
        b.enable()
        b.open(pack)
        eventually("both devices show LZ HAWK") { a.heading() == 270 && b.heading() == 270 }
        return OpDk(env, colin, sam, pack, a, b)
    }

    /** Waits until everything the device made in [pack] is the pack's: nothing queued, out, or taken and not yet confirmed. */
    private suspend fun PackDevice.sent(pack: String) {
        eventually("$label has sent everything") { stored(pack)?.pending?.isEmpty() == true }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun setHeading(value: Int): JsonObject = json("""{"type": "set", "item": "lz-1", "path": ["flightData", "landingHeading"], "value": $value}""")

    private fun setNotes(value: String): JsonObject = json("""{"type": "set", "item": "lz-1", "path": ["notes"], "value": "$value"}""")

    private fun setAnswer(key: String, value: String): JsonObject = json("""{"type": "set", "item": "br-1", "path": ["answers", "$key"], "value": {"value": "$value"}}""")

    private fun answer(data: JsonElement?, key: String): String? =
        ((((data as? JsonObject)?.get("answers") as? JsonObject)?.get(key) as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull

    private fun heading(data: JsonElement?): Int? =
        ((data as? JsonObject)?.get("flightData") as? JsonObject)?.get("landingHeading")?.jsonPrimitive?.intOrNull

    private fun notes(data: JsonElement?): String? = ((data as? JsonObject)?.get("notes") as? JsonPrimitive)?.contentOrNull

    private fun PackDevice.heading(): Int? = heading(item("lz-1")?.data)

    private fun PackDevice.notes(): String? = notes(item("lz-1")?.data)
}
