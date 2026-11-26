package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.ITEM
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.SetLines
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Backstops, FLOTs and phase lines in a Mission Pack open on two devices, Colin's (A, the owner) and Sam's (B), as the web keeps them: a backstop a graphic of the
 * LZ/PZ item, a FLOT and a phase line in the pack's control measures, one `route` item with no routes (`packMeasuresFor`), made by the first line drawn and used
 * by every one after it, from either device. Each change is checked on the other device's open document and in what the pack holds; in a finished pack nothing is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasuresPackTwoDevicesTest {
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object {
        /** Longer than a poll (3 s), and the sends either side of it. */
        const val POLL = 4_000L
    }

    private val a1 = LatLon(34.52, -84.11)
    private val a2 = LatLon(34.52, -84.09)
    private val a3 = LatLon(34.53, -84.08)

    private fun twoDevices(body: suspend TestScope.(a: PackEditorRig, b: PackEditorRig) -> Unit) = runTest {
        val b = PackEditorRig(this)
        val a = b.other(COLIN)
        try {
            b.opened()
            a.start()
            a.openPack()
            a.openItem(ITEM)
            body(a, b)
        } finally {
            a.close()
            b.close()
        }
    }

    private suspend fun PackEditorRig.draw(kind: MeasureKind, vararg at: LatLon, name: String? = null) {
        assertNull(measures.start(kind))
        at.forEach { assertTrue(measures.addPoint(it)) }
        assertNull(measures.finish(name))
        advance(STILL)
    }

    private fun PackEditorRig.measuresSet(): RouteSet = checkNotNull(measuresSession.active.value) { "the control measures are open" }

    private fun PackEditorRig.measuresItem(): String = checkNotNull(PackRef.parse(measuresSet().id)).item

    // The `route` items the pack was made with or given since, by their uuid, as its log has them.
    private fun PackEditorRig.routeItems(): List<String> =
        server.log(PACK).filter { (it["type"] as? JsonPrimitive)?.content == "item.create" && (it["op"]?.jsonObject?.get("kind") as? JsonPrimitive)?.content == "route" }
            .map { it.getValue("item").jsonPrimitive.content }

    @Test
    fun `the first line drawn makes the pack's control measures, the other device opens them, and a flip there is seen here`() = twoDevices { a, b ->
        b.draw(MeasureKind.FLOT, a1, a2)
        val item = b.measuresItem()
        val held = SetLines.fromData(b.server.data(PACK, item), SetLines.FLOTS)
        assertEquals("one FLOT in the pack", 1, held.size)
        assertEquals("no routes: control measures", 0, b.serverData(item).getValue("routes").jsonArray.size)
        val created = b.server.log(PACK).single { (it["item"] as? JsonPrimitive)?.content == item && (it["type"] as? JsonPrimitive)?.content == "item.create" }
        assertTrue(created.getValue("summary").jsonPrimitive.content.contains("started the pack's control measures"))

        a.advance(POLL)
        a.openItem(item)
        assertEquals("opened beside the LZ/PZ, in the control measures' session", PackRef.localId(PACK, item), a.measuresSession.active.value?.id)
        val flotId = SetLines.of(a.measuresSet(), SetLines.FLOTS).single().getValue("id").jsonPrimitive.content
        assertNull(a.measures.flipLine(HeldMeasure.Line(a.measuresSet().id, SetLines.FLOTS, flotId)))
        a.advance(STILL)
        b.advance(POLL)
        assertTrue("flipped on Sam's device", SetLines.of(b.measuresSet(), SetLines.FLOTS).single().getValue("flipped").jsonPrimitive.boolean)
        assertTrue("and in the pack", SetLines.fromData(b.serverData(item), SetLines.FLOTS).single().getValue("flipped").jsonPrimitive.boolean)
    }

    @Test
    fun `a line drawn on the other device joins the same control measures, never a second item`() = twoDevices { a, b ->
        b.draw(MeasureKind.FLOT, a1, a2)
        val item = b.measuresItem()
        a.advance(POLL)
        // Colin has not opened them: his phase line finds the pack's control measures, opens them and goes in.
        a.draw(MeasureKind.PHASE_LINE, a2, a3, name = "red")
        assertEquals(item, a.measuresItem())
        assertEquals(listOf(item), a.routeItems())
        b.advance(POLL)
        assertEquals(listOf("RED"), SetLines.of(b.measuresSet(), SetLines.PHASE_LINES).map { it.getValue("name").jsonPrimitive.content })
        assertEquals(1, SetLines.of(b.measuresSet(), SetLines.FLOTS).size)
        // A phase line renamed is seen by its name on the other device.
        val pl = SetLines.of(b.measuresSet(), SetLines.PHASE_LINES).single().getValue("id").jsonPrimitive.content
        assertNull(b.measures.renameLine(HeldMeasure.Line(b.measuresSet().id, SetLines.PHASE_LINES, pl), "blue"))
        b.advance(STILL)
        a.advance(POLL)
        assertEquals("BLUE", SetLines.of(a.measuresSet(), SetLines.PHASE_LINES).single().getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `a backstop placed on one device and its arrow moved on the other is the same on both and in the pack, the arrow sent whole`() = twoDevices { a, b ->
        b.draw(MeasureKind.BACKSTOP, a1, a2)
        val id = (b.diagrams.active.value!!.graphics.backstops.orEmpty().single() as JsonObject).getValue("id").jsonPrimitive.content
        a.advance(POLL)
        val theirs = DiagramOps.graphic(checkNotNull(a.diagrams.active.value), "backstops", id)
        assertNotNull("on Colin's open LZ/PZ", theirs)

        val arrow = json("""{"tail": {"lat": 34.519, "lon": -84.1}, "tip": {"lat": 34.505, "lon": -84.097}}""")
        a.diagrams.edit("Move backstop") { d -> DiagramOps.replaceGraphic(d, "backstops", JsonPrimitive(id), JsonObject(theirs!! + ("arrow" to arrow))) }
        a.advance(STILL)
        val op = a.sent().last().single()
        assertEquals("one patch of the backstop", "patch", op.getValue("type").jsonPrimitive.content)
        assertEquals(arrow, op.getValue("value").jsonObject.getValue("arrow"))

        b.advance(POLL)
        assertEquals(arrow, DiagramOps.graphic(checkNotNull(b.diagrams.active.value), "backstops", id)?.get("arrow"))
        val inPack = b.serverData().getValue("graphics").jsonObject.getValue("backstops").jsonArray.single().jsonObject
        assertEquals(arrow, inPack["arrow"])
    }

    @Test
    fun `in a finished pack nothing is drawn and no line is changed, each refused in words before anything is sent`() = twoDevices { a, b ->
        b.draw(MeasureKind.FLOT, a1, a2)
        val set = b.measuresSet()
        val flotId = SetLines.of(set, SetLines.FLOTS).single().getValue("id").jsonPrimitive.content
        b.server.finish(PACK, COLIN.id)
        b.advance(POLL)
        val sentBefore = b.sent().size
        assertNotNull(b.measures.refusal(MeasureKind.FLOT))
        assertNotNull(b.measures.start(MeasureKind.PHASE_LINE))
        assertNotNull(b.measures.refusal(MeasureKind.BACKSTOP))
        assertNotNull(b.measures.flipLine(HeldMeasure.Line(set.id, SetLines.FLOTS, flotId)))
        assertNotNull(b.measures.deleteLine(HeldMeasure.Line(set.id, SetLines.FLOTS, flotId)))
        assertNotNull(b.measures.renameSet("OTHER"))
        b.advance(STILL)
        assertEquals("nothing sent", sentBefore, b.sent().size)
        assertEquals(1, SetLines.of(b.measuresSet(), SetLines.FLOTS).size)
    }
}
