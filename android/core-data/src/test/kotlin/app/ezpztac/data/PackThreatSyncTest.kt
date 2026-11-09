package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.data.PackEditorRig.Companion.packTest
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * The sets of threats of an open mission pack (the owner's decision of 2026-10-12, `AGENTS.md` §2): on the map in place of the person's own, every member's to change, and
 * what is each person's own (which threats they hid, a terrain mask) kept out of what is sent. Each move between devices is checked on the other device's own map and in the
 * server's data, never only in what was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PackThreatSyncTest {
    @get:Rule
    val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

    private val item = "ts-1"
    private val local = PackRef.localId(PACK, item)

    private val sa6 = json(
        """{"threats": [{"id": "t-1", "name": "SA-6", "milstdId": "SHGPEWRR------", "lat": 34.5, "lon": -84.5, "information": "", "source": "SOF", "showThreat": true,
            "radars": [{"type": 1, "rangeNmi": 25, "antennaHeightFt": 30, "aglNotMsl": true, "showMask": true, "showRangeRings": true,
                        "bands": [{"altFt": 300, "color": "#ff0000", "alpha": 0.5, "colorIndex": 1, "viewable": true}]}]}]}""",
    )

    private fun threat(name: String = "SA-8", lat: Double = 34.6, lon: Double = -84.6) = Threat(name, "SHGPEWRR------", lat, lon, "", "SOF", radars = Radars.defaultPair())

    private suspend fun TestScope.opened(rig: PackEditorRig, vararg items: JsonObject = arrayOf(create(item, "threatset", "TEAM THREATS", sa6))) {
        rig.pack(PACK, "OP DK", *items)
        rig.start()
        rig.openPack()
        rig.advance(1)
        runCurrent()
    }

    private fun PackEditorRig.threatIds() = threats.entries.value.map { it.id }

    private fun PackEditorRig.savedThreats(of: String = item) = serverData(of).getValue("threats").jsonArray.map { it.jsonObject }

    @Test
    fun `a pack's sets come on to the map in place of the person's own, and go when it is left`() = packTest { rig ->
        val own = rig.threats.add(threat("MINE"))
        opened(rig)
        assertEquals("the pack's alone", listOf("TEAM THREATS"), rig.threats.sets.value.map { it.name })
        assertEquals(listOf("t-1"), rig.threatIds())
        assertFalse("not the person's own", own in rig.threatIds())

        rig.workspace.closePack()
        runCurrent()
        rig.advance(1)
        assertEquals("their own again", listOf(own), rig.threatIds())
    }

    @Test
    fun `a threat moved on one device shows on the other, and in the pack`() = packTest { rig ->
        opened(rig)
        val colin = rig.other(COLIN)
        colin.start()
        colin.openPack()
        colin.advance(1)
        assertEquals(listOf("t-1"), colin.threatIds())

        assertTrue(rig.threats.move("t-1", 34.75, -84.25))
        rig.advance(STILL + 1_000)
        assertEquals(34.75, rig.savedThreats().single().getValue("lat").jsonPrimitive.content.toDouble(), 0.0)

        colin.engine.refresh()
        colin.advance(1)
        val there = colin.threats.entries.value.single().threat
        assertEquals("on the other device's own map", 34.75, there.lat, 0.0)
        assertEquals(-84.25, there.lon, 0.0)
        assertEquals("nothing goes back", 1, rig.server.batches.size)
        colin.close()
    }

    @Test
    fun `what is each person's own is never sent`() = packTest { rig ->
        opened(rig)
        val mask = json("""{"for": "x", "png": "AAAA"}""")
        assertTrue(rig.threats.setVisible("t-1", false))
        assertTrue(rig.threats.setMask("t-1", mask))
        rig.advance(STILL + 1_000)
        assertEquals("hiding one and a mask change nothing in the pack", emptyList<Any>(), rig.sent())

        assertTrue(rig.threats.move("t-1", 34.75, -84.25))
        rig.advance(STILL + 1_000)
        val sent = rig.sent().flatten().toString()
        assertFalse(sent, sent.contains("AAAA"))
        assertFalse(sent, sent.contains("\"mask\""))
        assertEquals("still hidden here", false, rig.threats.entries.value.single().visible)
        assertNotNull("and its mask kept", rig.threats.masks.value["t-1"])
        assertEquals("true", rig.savedThreats().single().getValue("showThreat").jsonPrimitive.content)
    }

    @Test
    fun `someone else's change keeps what this person hid`() = packTest { rig ->
        opened(rig)
        rig.threats.setVisible("t-1", false)
        rig.advance(STILL + 1_000)

        val moved = json(sa6.toString().replace("34.5", "35.25"))
        rig.theirs(JsonObject(linkedMapOf("type" to JsonPrimitive("set"), "item" to JsonPrimitive(item), "path" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("threats"))), "value" to moved.getValue("threats"))))
        rig.advance(1)

        val now = rig.threats.entries.value.single()
        assertEquals(35.25, now.threat.lat, 0.0)
        assertFalse("hidden still", now.visible)
        assertEquals("taken, and nothing sent back", emptyList<Any>(), rig.sent())
    }

    @Test
    fun `a new threat goes into the pack, in a set made for it`() = packTest { rig ->
        opened(rig, *emptyArray())
        val id = rig.threats.add(threat("SA-8"))
        rig.advance(STILL + 1_000)

        assertEquals(listOf(id), rig.threatIds())
        val set = rig.threats.sets.value.single()
        val ref = checkNotNull(PackRef.parse(set.id))
        assertEquals(PACK, ref.pack)
        assertEquals(id, rig.savedThreats(ref.item).single().getValue("id").jsonPrimitive.content)
        assertEquals("THREATS", set.name)
        assertEquals("one create, and no second copy", 1, rig.sent().flatten().count { it.getValue("type").jsonPrimitive.content == "item.create" })
    }

    @Test
    fun `new threats go into the set on the map when there is one`() = packTest { rig ->
        opened(rig)
        val id = rig.threats.add(threat("SA-8"))
        rig.advance(STILL + 1_000)
        assertEquals(listOf("t-1", id), rig.savedThreats().map { it.getValue("id").jsonPrimitive.content })
        assertEquals(1, rig.threats.sets.value.size)
    }

    @Test
    fun `a set closed here stays closed, stays in the pack, and comes back when opened`() = packTest { rig ->
        opened(rig)
        rig.workspace.closeItem(item)
        runCurrent()
        rig.advance(1)
        assertEquals(emptyList<String>(), rig.threatIds())
        assertNotNull("in the pack", rig.server.data(PACK, item))

        rig.theirs(JsonObject(linkedMapOf("type" to JsonPrimitive("item.rename"), "item" to JsonPrimitive(item), "name" to JsonPrimitive("RENAMED"))))
        rig.advance(1)
        assertEquals("a change by someone else does not bring it back", emptyList<String>(), rig.threatIds())

        assertTrue(rig.workspace.openItem(item))
        runCurrent()
        rig.advance(1)
        assertEquals(listOf("t-1"), rig.threatIds())
        assertEquals("RENAMED", rig.threats.sets.value.single().name)
    }

    @Test
    fun `a change a read-only pack will not take is put back and said`() = packTest { rig ->
        opened(rig)
        rig.server.finish(PACK, COLIN.id)
        rig.engine.refresh()
        runCurrent()
        rig.advance(1)

        rig.threats.move("t-1", 34.75, -84.25)
        rig.advance(STILL + 1_000)
        assertEquals("put back", 34.5, rig.threats.entries.value.single().threat.lat, 0.0)
        assertEquals("nothing sent", emptyList<Any>(), rig.sent())
        assertTrue(rig.notices.toString(), rig.notices.any { it is PackNotice.Refused && it.reason == PackItemStore.READ_ONLY })
    }

    @Test
    fun `a set removed by someone else leaves the map, and a change not yet sent is kept`() = packTest { rig ->
        opened(rig)
        rig.threats.move("t-1", 34.75, -84.25)
        rig.theirs(JsonObject(linkedMapOf("type" to JsonPrimitive("item.delete"), "item" to JsonPrimitive(item))))
        rig.advance(STILL + 1_000)

        assertEquals("off the map", emptyList<String>(), rig.threatIds())
        val saved = rig.keeper.records.single().version
        assertEquals("TEAM THREATS (my edits)", saved.name)
        assertEquals("threatset", saved.kind)
        val removed = rig.notices.filterIsInstance<PackNotice.ItemRemoved>().single()
        assertEquals(local, removed.localId)
        assertNotNull("said what was kept", removed.kept)
    }

    @Test
    fun `a set removed with nothing unsent keeps nothing`() = packTest { rig ->
        opened(rig)
        rig.theirs(JsonObject(linkedMapOf("type" to JsonPrimitive("item.delete"), "item" to JsonPrimitive(item))))
        rig.advance(1)
        assertEquals(emptyList<String>(), rig.threatIds())
        assertEquals(0, rig.keeper.records.size)
        assertNull(rig.notices.filterIsInstance<PackNotice.ItemRemoved>().single().kept)
    }

    @Test
    fun `an account without Threats is shown none of a pack's`() = packTest { rig ->
        rig.threatsOn = false
        opened(rig)
        assertEquals(emptyList<String>(), rig.threatIds())
        assertTrue(rig.threats.sets.value.isEmpty())
        rig.threats.add(threat("MINE"))
        rig.advance(STILL + 1_000)
        assertEquals("the person's own, not the pack's", emptyList<Any>(), rig.sent())
    }

    @Test
    fun `a rename goes to the pack as one`() = packTest { rig ->
        opened(rig)
        assertTrue(rig.threats.rename(local, "new name"))
        rig.advance(STILL + 1_000)
        val ops = rig.sent().flatten()
        assertEquals(ops.toString(), "item.rename", ops.single().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `a change made as the pack is left reaches it`() = packTest { rig ->
        opened(rig)
        rig.threats.move("t-1", 34.75, -84.25)
        rig.workspace.closePack()
        runCurrent()
        rig.advance(1)
        assertEquals(34.75, rig.savedThreats().single().getValue("lat").jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `seeing one's own change back asks nothing more`() = packTest { rig ->
        opened(rig)
        rig.threats.move("t-1", 34.75, -84.25)
        rig.advance(STILL + 1_000)
        rig.engine.refresh()
        rig.advance(10_000)
        assertEquals(1, rig.sent().size)
    }
}
