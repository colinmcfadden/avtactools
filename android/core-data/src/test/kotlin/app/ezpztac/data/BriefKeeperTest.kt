package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.SAM
import app.ezpztac.missionpacks.InMemoryPackStore
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.missionpacks.PackStore
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException

/**
 * The brief being set up, where it is kept: a record in the person's Library outside a pack, the pack's brief item inside one (docs/MISSION_BRIEF.md §3; the web's
 * useBrief). Over the pack rig's real engine and fake pack server, and a library on the fake sync server, on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefKeeperTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private class Env(val rig: PackEditorRig, val last: InMemoryLastBrief, val keeper: BriefKeeper, val refusals: MutableList<String>)

    /** The device's copy of its packs, whose writes fail while [failing] (the disk full). */
    private class FlakyPackStore(private val inner: PackStore = InMemoryPackStore()) : PackStore by inner {
        var failing = false

        override suspend fun write(before: PackSession?, after: PackSession, me: Int) {
            if (failing) throw IOException("No space left on the device")
            inner.write(before, after, me)
        }
    }

    private fun keeperTest(canKeep: Boolean = true, packStore: PackStore = InMemoryPackStore(), block: suspend TestScope.(Env) -> Unit) = runTest {
        val rig = PackEditorRig(this, packStore = packStore)
        val last = InMemoryLastBrief()
        var n = 0
        val keeper = BriefKeeper(rig.library.repository, rig.scheduler, rig.engine, last, rig.scope, canKeep = { canKeep }, newId = { "id-${++n}" })
        val refusals = mutableListOf<String>()
        rig.scope.launch { keeper.refusals.collect { refusals += it } }
        try {
            block(Env(rig, last, keeper, refusals))
        } finally {
            rig.close()
        }
    }

    private fun answer(key: String, value: String): (JsonObject) -> JsonObject = { doc ->
        val answers = (doc["answers"] as? JsonObject).orEmpty()
        JsonObject(doc + ("answers" to JsonObject(answers + (key to buildJsonObject { put("value", value) }))))
    }

    private fun JsonObject.answer(key: String): String? = (this["answers"] as? JsonObject)?.get(key)?.jsonObject?.get("value")?.jsonPrimitive?.content

    private suspend fun Env.briefs() = rig.library.repository.records(RecordKind.BRIEF)

    // -- In the Library -------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is kept before a change, and a change is written once the person has been still`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        runCurrent()
        assertNull(env.keeper.documentNow(null).doc)
        env.keeper.change(null, answer("2.b", "SEIZE OBJ BLUE"))
        assertEquals(BriefSave.SAVING, env.keeper.documentNow(null).save)
        assertEquals("SEIZE OBJ BLUE", env.keeper.documentNow(null).doc!!.answer("2.b"))
        advanceTimeBy(BriefKeeper.SAVE_DELAY_MS - 1)
        runCurrent()
        assertTrue("written before the person was still", env.briefs().isEmpty())
        advanceTimeBy(2)
        runCurrent()
        val record = env.briefs().single()
        assertEquals("MISSION BRIEF", record.name)
        assertEquals("SEIZE OBJ BLUE", record.data.answer("2.b"))
        assertEquals("amb", record.data["template"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(record.uuid, env.last.id(SAM.id))                                                  // the one opened next time
        assertEquals(BriefSave.SAVED, env.keeper.documentNow(null).save)
        assertTrue(env.rig.scheduler.requested > 0)
    }

    @Test
    fun `later changes edit the same record, a burst of them written once`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "ONE"))
        advanceTimeBy(BriefKeeper.SAVE_DELAY_MS + 1)
        runCurrent()
        val first = env.briefs().single()
        repeat(5) { env.keeper.change(null, answer("2.b", "TWO $it")) }
        advanceTimeBy(BriefKeeper.SAVE_DELAY_MS + 1)
        runCurrent()
        val record = env.briefs().single()
        assertEquals(first.uuid, record.uuid)
        assertEquals("TWO 4", record.data.answer("2.b"))
        assertEquals("five changes written more than once", first.localVersion + 1, record.localVersion)
    }

    @Test
    fun `a change waiting is written at once when the setup closes`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "NOW"))
        env.keeper.flush()
        assertEquals("NOW", env.briefs().single().data.answer("2.b"))
        assertEquals(BriefSave.SAVED, env.keeper.documentNow(null).save)
    }

    @Test
    fun `a change that changes nothing is no change`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null) { it }
        assertNull(env.keeper.documentNow(null).save)
        advanceTimeBy(BriefKeeper.SAVE_DELAY_MS + 1)
        runCurrent()
        assertTrue(env.briefs().isEmpty())
    }

    @Test
    fun `the brief opened last comes back for its account, and never for another`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "SAM'S"))
        env.keeper.flush()
        val again = BriefKeeper(env.rig.library.repository, env.rig.scheduler, env.rig.engine, env.last, env.rig.scope)
        again.use(SAM.id)
        assertEquals("SAM'S", again.documentNow(null).doc!!.answer("2.b"))
        assertEquals(BriefSave.SAVED, again.documentNow(null).save)
        val colin = BriefKeeper(env.rig.library.repository, env.rig.scheduler, env.rig.engine, env.last, env.rig.scope)
        colin.use(COLIN.id)
        assertNull(colin.documentNow(null).doc)
    }

    @Test
    fun `another account's turn writes the change still waiting first, as the one before's`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "SAM'S"))
        env.keeper.use(COLIN.id)
        assertEquals("SAM'S", env.briefs().single().data.answer("2.b"))
        assertEquals(env.briefs().single().uuid, env.last.id(SAM.id))
        assertNull(env.keeper.documentNow(null).doc)
        assertNull(env.last.id(COLIN.id))
        env.keeper.use(null)
        env.keeper.change(null, answer("2.b", "NOBODY'S"))                                                // signed out: nothing to keep it for
        assertNull(env.keeper.documentNow(null).doc)
    }

    @Test
    fun `a brief deleted elsewhere is started again under a new identity`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "FIRST"))
        env.keeper.flush()
        val first = env.briefs().single().uuid
        env.rig.library.repository.delete(RecordKind.BRIEF, first)
        env.keeper.change(null, answer("2.b", "AGAIN"))
        env.keeper.flush()
        val second = env.briefs().single()
        assertNotEquals(first, second.uuid)
        assertEquals("AGAIN", second.data.answer("2.b"))
        assertEquals(second.uuid, env.last.id(SAM.id))
        // ... and one deleted before the device next opens it is not opened, and the next change starts again under a new identity.
        env.rig.library.repository.delete(RecordKind.BRIEF, second.uuid)
        val next = BriefKeeper(env.rig.library.repository, env.rig.scheduler, env.rig.engine, env.last, env.rig.scope)
        next.use(SAM.id)
        assertNull(next.documentNow(null).doc)
        next.change(null, answer("2.b", "THIRD"))
        next.flush()
        val third = env.briefs().single()
        assertNotEquals(second.uuid, third.uuid)
        assertEquals(third.uuid, env.last.id(SAM.id))
    }

    @Test
    fun `a brief not on the device yet is still asked for, and opened once a sync brings it`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "ON ANOTHER DEVICE"))
        env.keeper.flush()
        val record = env.briefs().single()
        // A shared tablet whose plans were cleared: the brief is remembered, and not on the device until the next sync.
        env.rig.library.repository.delete(RecordKind.BRIEF, record.uuid)
        val tablet = BriefKeeper(env.rig.library.repository, env.rig.scheduler, env.rig.engine, env.last, env.rig.scope)
        tablet.use(SAM.id)
        assertNull(tablet.documentNow(null).doc)
        assertEquals(record.uuid, env.last.id(SAM.id))                                                   // not forgotten
    }

    @Test
    fun `the setup opening again shows the version a sync brought, and the next change is laid over it`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "MINE"))
        env.keeper.flush()
        val record = env.briefs().single()
        env.rig.library.repository.edit(RecordKind.BRIEF, record.uuid, data = answer("2.d", "THEIRS")(record.data))   // another device's, as a sync writes it
        assertNull(env.keeper.documentNow(null).doc!!.answer("2.d"))
        env.keeper.open()
        assertTrue("nothing shown while it is read", env.keeper.documentNow(null).loading)
        runCurrent()
        assertEquals("THEIRS", env.keeper.documentNow(null).doc!!.answer("2.d"))
        assertEquals(BriefSave.SAVED, env.keeper.documentNow(null).save)
        env.keeper.change(null, answer("2.e", "MORE"))
        env.keeper.flush()
        val now = env.briefs().single().data
        assertEquals("MINE", now.answer("2.b"))
        assertEquals("THEIRS", now.answer("2.d"))
        assertEquals("MORE", now.answer("2.e"))
    }

    @Test
    fun `a version a sync brought while a change waited is kept, and the change laid over it`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "ONE"))
        env.keeper.flush()
        val record = env.briefs().single()
        env.keeper.change(null, answer("2.c", "MINE"))                                                     // waits for the person to be still
        env.rig.library.repository.edit(RecordKind.BRIEF, record.uuid, data = answer("2.d", "THEIRS")(record.data))
        advanceTimeBy(BriefKeeper.SAVE_DELAY_MS + 1)
        runCurrent()
        val now = env.briefs().single().data
        assertEquals("THEIRS", now.answer("2.d"))                                                          // not written over
        assertEquals("MINE", now.answer("2.c"))
        assertEquals("THEIRS", env.keeper.documentNow(null).doc!!.answer("2.d"))                          // and shown
    }

    @Test
    fun `an account that cannot keep records in its Library is told so, and nothing is written`() = keeperTest(canKeep = false) { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "KEPT HERE"))
        assertEquals(BriefSave.NOT_KEPT, env.keeper.documentNow(null).save)
        assertEquals("KEPT HERE", env.keeper.documentNow(null).doc!!.answer("2.b"))
        advanceTimeBy(BriefKeeper.SAVE_DELAY_MS + 1)
        runCurrent()
        env.keeper.flush()
        assertTrue(env.briefs().isEmpty())
    }

    // -- In a pack --------------------------------------------------------------------------------------------------------------------

    private suspend fun TestScope.packOpen(env: Env) {
        env.rig.pack()
        env.rig.start()
        env.rig.openPack()
        env.keeper.use(SAM.id)
        runCurrent()
    }

    private fun Env.packState() = rig.engine.open.value!!

    private fun Env.packBrief(): JsonObject? = BriefKeeper.briefItemOf(packState())?.data as? JsonObject

    @Test
    fun `in a pack the first change makes the pack's brief, named for the pack, with its sentence`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "SEIZE OBJ BLUE"))
        assertEquals("SEIZE OBJ BLUE", env.keeper.documentNow().doc!!.answer("2.b"))                   // shown at once
        env.rig.advance(PackEditorRig.STILL)
        val create = env.rig.sent().flatten().single { it["type"]!!.jsonPrimitive.content == "item.create" }
        assertEquals("brief", create["kind"]!!.jsonPrimitive.content)
        assertEquals("OP DK BRIEF", create["name"]!!.jsonPrimitive.content)
        assertEquals("br-id-1", create["item"]!!.jsonPrimitive.content)
        assertEquals("Sam B. added the brief \"OP DK BRIEF\".", create["summary"]!!.jsonPrimitive.content)
        assertEquals("SEIZE OBJ BLUE", env.rig.serverData("br-id-1").answer("2.b"))
        assertEquals("SEIZE OBJ BLUE", env.packBrief()!!.answer("2.b"))
        assertNull(env.keeper.documentNow().save)                                                        // nothing of the Library's
        assertTrue(env.briefs().isEmpty())
    }

    @Test
    fun `a later change goes as the pack's operations on its brief, each with its one sentence`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "ONE"))
        env.rig.advance(PackEditorRig.STILL)
        val before = env.rig.sent().size
        env.keeper.change(env.packState(), answer("2.c", "TWO"))
        env.rig.advance(PackEditorRig.STILL)
        val ops = env.rig.sent().drop(before).flatten()
        assertTrue(ops.isNotEmpty())
        assertTrue(ops.none { it["type"]!!.jsonPrimitive.content == "item.create" })
        assertTrue(ops.all { it["summary"] == JsonPrimitive("Sam B. changed the brief \"OP DK BRIEF\".") })
        val server = env.rig.serverData("br-id-1")
        assertEquals("ONE", server.answer("2.b"))
        assertEquals("TWO", server.answer("2.c"))
    }

    @Test
    fun `someone else's change to the brief is kept when this person changes another answer`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "SAM'S"))
        env.rig.advance(PackEditorRig.STILL)
        val colinsChange = buildJsonObject {
            put("type", "set")
            put("item", "br-id-1")
            put("path", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("answers"), JsonPrimitive("2.d"))))
            put("value", buildJsonObject { put("value", "COLIN'S") })
            put("summary", "Colin M. changed the brief \"OP DK BRIEF\".")
        }
        env.rig.theirs(colinsChange)
        assertEquals("COLIN'S", env.keeper.documentNow().doc!!.answer("2.d"))
        env.keeper.change(env.packState(), answer("2.e", "SAM AGAIN"))
        env.rig.advance(PackEditorRig.STILL)
        val server = env.rig.serverData("br-id-1")
        assertEquals("COLIN'S", server.answer("2.d"))
        assertEquals("SAM AGAIN", server.answer("2.e"))
        assertEquals("SAM'S", server.answer("2.b"))
    }

    @Test
    fun `a change made against an older view of the brief is worked out against the pack as it is when it is sent`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "SAM'S"))
        env.rig.advance(PackEditorRig.STILL)
        val seen = env.packState()                                               // what the setup showed when the person began typing
        val colinsChange = buildJsonObject {
            put("type", "set")
            put("item", "br-id-1")
            put("path", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("answers"), JsonPrimitive("2.d"))))
            put("value", buildJsonObject { put("value", "COLIN'S") })
            put("summary", "Colin M. changed the brief \"OP DK BRIEF\".")
        }
        env.rig.theirs(colinsChange)                                             // reaches the device before the person's change is sent
        env.keeper.change(seen, answer("2.e", "SAM AGAIN"))
        env.rig.advance(PackEditorRig.STILL)
        val server = env.rig.serverData("br-id-1")
        assertEquals("COLIN'S", server.answer("2.d"))                            // not put back by a change worked out without it
        assertEquals("SAM AGAIN", server.answer("2.e"))
    }

    @Test
    fun `a finished pack still takes changes to its brief`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "BEFORE"))
        env.rig.advance(PackEditorRig.STILL)
        env.rig.server.finish(PACK, by = COLIN.id)
        env.rig.engine.refresh()
        env.rig.advance(PackEditorRig.STILL)
        assertTrue("the pack is finished", env.packState().readOnly)
        env.keeper.change(env.packState(), answer("2.b", "AFTER"))
        env.rig.advance(PackEditorRig.STILL)
        assertEquals("AFTER", env.rig.serverData("br-id-1").answer("2.b"))
        assertTrue(env.refusals.isEmpty())
    }

    @Test
    fun `a change for a pack no longer open is sent nowhere, and kept in the Library as the pack's brief, my edits`() = keeperTest { env ->
        packOpen(env)
        val state = env.packState()
        env.rig.workspace.closePack()
        runCurrent()
        env.keeper.change(state, answer("2.b", "TOO LATE"))
        env.rig.advance(PackEditorRig.STILL)
        assertTrue(env.rig.sent().isEmpty())
        val kept = env.briefs().single()
        assertEquals("OP DK BRIEF (my edits)", kept.name)
        assertEquals("TOO LATE", kept.data.answer("2.b"))
        assertEquals(listOf(BriefKeeper.keptWords("OP DK BRIEF (my edits)")), env.refusals)
    }

    @Test
    fun `changes made one after another reach the pack in that order, and the last is what shows`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "FIRST"))
        env.keeper.change(env.packState(), answer("2.b", "SECOND"))
        assertEquals("SECOND", env.keeper.documentNow().doc!!.answer("2.b"))
        env.rig.advance(PackEditorRig.STILL)
        assertEquals("SECOND", env.rig.serverData("br-id-1").answer("2.b"))
        assertEquals("SECOND", env.keeper.documentNow().doc!!.answer("2.b"))
    }

    @Test
    fun `flush sends a change to the pack's brief made just before it`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "NOW"))
        env.keeper.flush()                                                                                  // nothing else ran since the change
        assertEquals("NOW", env.packBrief()!!.answer("2.b"))                                               // on the device, in the pack
    }

    @Test
    fun `the first change in a finished pack makes its brief`() = keeperTest { env ->
        packOpen(env)
        env.rig.server.finish(PACK, by = COLIN.id)
        env.rig.engine.refresh()
        env.rig.advance(PackEditorRig.STILL)
        assertTrue("the pack is finished", env.packState().readOnly)
        env.keeper.change(env.packState(), answer("2.b", "AFTER THE END"))
        env.rig.advance(PackEditorRig.STILL)
        assertEquals("AFTER THE END", env.rig.serverData("br-id-1").answer("2.b"))
        assertTrue(env.refusals.isEmpty())
    }

    @Test
    fun `a change the pack calls no change sends nothing, and is not called refused in a finished pack`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "BEFORE"))
        env.rig.advance(PackEditorRig.STILL)
        env.rig.server.finish(PACK, by = COLIN.id)
        env.rig.engine.refresh()
        env.rig.advance(PackEditorRig.STILL)
        val sent = env.rig.sent().size
        // 1 and 1.0 are one number to the pack (JavaScript's), two to kotlinx.
        env.keeper.change(env.packState()) { doc -> JsonObject(doc + ("version" to JsonPrimitive(1.0))) }
        env.rig.advance(PackEditorRig.STILL)
        assertEquals(sent, env.rig.sent().size)
        assertTrue(env.refusals.isEmpty())
    }

    private suspend fun TestScope.paused(env: Env) {
        env.rig.server.setFeature(SAM.id, false)                                                           // an admin turned Mission Packs off for Sam
        env.rig.engine.refresh()
        runCurrent()
        assertEquals(PackStatus.PAUSED, env.packState().status)
    }

    @Test
    fun `changes made while the pack is paused wait, shown, and go once the account is back`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "BEFORE"))
        env.rig.advance(PackEditorRig.STILL)
        paused(env)
        val sent = env.rig.sent().size
        env.keeper.change(env.packState(), answer("2.b", "WAITS"))
        env.rig.advance(10_000)
        env.keeper.change(env.packState(), answer("2.c", "ALSO"))
        env.rig.advance(10_000)
        assertEquals("WAITS", env.keeper.documentNow().doc!!.answer("2.b"))                               // shown, never put back
        assertEquals("ALSO", env.keeper.documentNow().doc!!.answer("2.c"))
        assertEquals(listOf(BriefKeeper.PAUSED_WAITS), env.refusals)                                       // said once
        assertEquals(sent, env.rig.sent().size)
        env.rig.server.setFeature(SAM.id, true)                                                            // ticked again, and the app enables packs once more
        env.rig.engine.enable(SAM)
        env.rig.advance(10_000)
        val server = env.rig.serverData("br-id-1")
        assertEquals("WAITS", server.answer("2.b"))
        assertEquals("ALSO", server.answer("2.c"))
        assertTrue(env.briefs().isEmpty())                                                                  // it went: nothing kept aside
    }

    @Test
    fun `a change still waiting when the pack closes is kept in the Library, and the person told`() = keeperTest { env ->
        packOpen(env)
        env.keeper.change(env.packState(), answer("2.b", "BEFORE"))
        env.rig.advance(PackEditorRig.STILL)
        paused(env)
        env.keeper.change(env.packState(), answer("2.c", "KEEP ME"))
        env.rig.advance(10_000)
        env.rig.workspace.closePack()
        runCurrent()
        val kept = env.briefs().single()
        assertEquals("OP DK BRIEF (my edits)", kept.name)
        assertEquals("BEFORE", kept.data.answer("2.b"))                                                    // the pack's brief, with the change laid over it
        assertEquals("KEEP ME", kept.data.answer("2.c"))
        assertEquals(BriefKeeper.keptWords("OP DK BRIEF (my edits)"), env.refusals.last())
    }

    @Test
    fun `a change the device cannot keep is said, still shown, and goes with the next one`() {
        val store = FlakyPackStore()
        keeperTest(packStore = store) { env ->
            packOpen(env)
            env.keeper.change(env.packState(), answer("2.b", "BEFORE"))
            env.rig.advance(PackEditorRig.STILL)
            store.failing = true
            env.keeper.change(env.packState(), answer("2.c", "FIRST"))
            env.rig.advance(PackEditorRig.STILL)
            assertEquals(listOf(BriefKeeper.NOT_ON_DEVICE), env.refusals)
            assertEquals("FIRST", env.keeper.documentNow().doc!!.answer("2.c"))
            store.failing = false
            env.keeper.change(env.packState(), answer("2.d", "SECOND"))
            env.rig.advance(PackEditorRig.STILL)
            val server = env.rig.serverData("br-id-1")
            assertEquals("FIRST", server.answer("2.c"))
            assertEquals("SECOND", server.answer("2.d"))
        }
    }

    @Test
    fun `a change that fails changes nothing, and ends nothing`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null) { error("a change no screen makes") }
        assertNull(env.keeper.documentNow(null).doc)
        packOpen(env)
        env.keeper.change(env.packState()) { error("a change no screen makes") }
        env.rig.advance(PackEditorRig.STILL)
        assertTrue(env.rig.sent().isEmpty())
        env.keeper.change(env.packState(), answer("2.b", "STILL WORKS"))
        env.rig.advance(PackEditorRig.STILL)
        assertEquals("STILL WORKS", env.rig.serverData("br-id-1").answer("2.b"))
    }

    @Test
    fun `while the device does not hold the pack yet nothing of its brief is shown`() = keeperTest { env ->
        env.rig.pack()
        env.rig.start()
        env.keeper.use(SAM.id)
        env.rig.server.offline = true                                                                       // no answer to the first load, and no copy on the device
        env.rig.engine.openPack(PACK)
        runCurrent()
        assertTrue(env.keeper.documentNow().loading)
        assertNull(env.keeper.documentNow().doc)
    }

    @Test
    fun `the Library's brief stays the Library's while a pack is open, and comes back after`() = keeperTest { env ->
        env.keeper.use(SAM.id)
        env.keeper.change(null, answer("2.b", "LIBRARY"))
        env.keeper.flush()
        packOpen(env)
        assertNull(env.keeper.documentNow().doc)                                                          // the pack has no brief yet
        env.rig.workspace.closePack()
        runCurrent()
        assertEquals("LIBRARY", env.keeper.documentNow().doc!!.answer("2.b"))
        assertNotNull(env.last.id(SAM.id))
    }
}
