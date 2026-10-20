package app.ezpztac.data

import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSets
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import app.ezpztac.network.FieldAnalysis
import app.ezpztac.network.HeadingSlope
import app.ezpztac.network.SlopeStats
import app.ezpztac.network.SlopeThresholds
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.network.Uh60Limits
import app.ezpztac.sync.FilePart
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Where a brief's plan comes from: the open pack, or the LZ/PZs and route sets open in this session (the web's planFromPack and planFromSession). */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefSourcesTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private class Env(
        val rig: PackEditorRig,
        val packs: MutableStateFlow<PackState?>,
        val openDiagrams: OpenDiagrams,
        val openSets: OpenRouteSets,
        val threats: MutableStateFlow<List<ThreatEntry>>,
        val allowed: MutableStateFlow<Boolean>,
        val reads: MutableList<String>,
        val emitted: MutableList<BriefInputs>,
        val slopes: MutableStateFlow<Map<String, SlopeState>>,
        val analysis: AnalysisService,
    )

    private val missionDoc = json("""{"version": 1, "routes": [{"id": "m-1", "name": "FROM THE FILE", "points": [{"id": "p1", "lat": 34.0, "lon": -84.0, "kind": "amps", "name": "SP"}, {"id": "p2", "lat": 34.1, "lon": -84.1, "kind": "amps", "name": "RP"}]}]}""")

    private fun sourcesTest(block: suspend TestScope.(Env) -> Unit) = runTest {
        val rig = PackEditorRig(this)
        val packs = MutableStateFlow<PackState?>(null)
        val openDiagrams = OpenDiagrams(InMemoryOpenDiagramStore())
        val openSets = OpenRouteSets(InMemoryOpenRouteSetStore())
        val threats = MutableStateFlow<List<ThreatEntry>>(emptyList())
        val allowed = MutableStateFlow(true)
        val reads = mutableListOf<String>()
        val slopes = MutableStateFlow<Map<String, SlopeState>>(emptyMap())
        val terrain = object : TerrainApi {
            override suspend fun analyzeField(at: LatLon): FieldAnalysis = error("not asked here")
            override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis = error("not asked here")
        }
        val analysis = AnalysisService(terrain, rig.diagrams, rig.scope, rig.dispatcher)
        val sources = BriefSources(
            packs, openDiagrams, openSets, rig.diagrams, rig.routes, rig.library.store as InMemorySyncStore,
            readSet = { uuid -> reads += uuid; RouteSets.parse(uuid, null, "MISSION.msnx", missionDoc) },
            threats = threats,
            threatsAllowed = allowed,
            profiles = flowOf(listOf(AircraftProfile())),
            missionAircraft = flowOf("uh60l"),
            slopes = slopes,
            boundaryKey = analysis::boundaryKey,
        )
        val emitted = mutableListOf<BriefInputs>()
        rig.scope.launch { sources.inputs.collect { emitted += it } }
        try {
            block(Env(rig, packs, openDiagrams, openSets, threats, allowed, reads, emitted, slopes, analysis))
        } finally {
            rig.close()
        }
    }

    private fun BriefInputs.ids(key: String) = plan[key]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    private fun BriefInputs.names(key: String) = plan[key]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun `outside a pack the plan is the open LZ-PZs in their order, the one on the map with its edits not yet written`() = sourcesTest { env ->
        val hawk = env.rig.diagramRepository.create(DiagramTarget(34.55, -84.11, "16S GD 66993 52949"), "LZ HAWK")
        val ibis = env.rig.diagramRepository.create(DiagramTarget(34.57, -84.10, "16S GD 67400 55180"), "PZ IBIS")
        env.rig.diagramRepository.create(DiagramTarget(34.60, -84.05, "16S GD 72000 58400"), "NOT OPEN")
        env.openDiagrams.add(ibis.id)
        env.openDiagrams.add(hawk.id)
        env.rig.diagrams.open(hawk.id)
        env.rig.diagrams.edit("Rename") { it.copy(name = "LZ HAWK 2") }                           // still in memory: not yet written
        runCurrent()
        val now = env.emitted.last()
        assertEquals(listOf(ibis.id, hawk.id), now.ids("lzs"))
        assertEquals(listOf("PZ IBIS", "LZ HAWK 2"), now.names("lzs"))
        assertEquals("LZ HAWK 2", now.plan["lzs"]!!.jsonArray[1].jsonObject["diagram"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(""), now.plan["name"])
        assertNull(now.pack)
    }

    @Test
    fun `the route sets open with routes are in it, and a mission's routes are read from its file once while it is the same`() = sourcesTest { env ->
        val repo = env.rig.library.repository
        val drawn = repo.create(RecordKind.ROUTE, "SKETCHED ROUTES", json("""{"version": 1, "routes": [{"id": "r-1", "name": "ALDERAAN", "points": []}]}"""))
        val empty = repo.create(RecordKind.ROUTE, "EMPTY", json("""{"version": 1, "routes": []}"""))
        val mission = repo.create(RecordKind.MISSION, "MISSION.msnx", json("""{"version": 1, "routes": []}"""), file = FilePart("MISSION.msnx", byteArrayOf(1, 2, 3)))
        listOf(drawn.uuid, empty.uuid, mission.uuid).forEach(env.openSets::add)
        runCurrent()
        val now = env.emitted.last()
        assertEquals(listOf(drawn.uuid, mission.uuid), now.ids("routeSets"))
        assertEquals("FROM THE FILE", now.plan["routeSets"]!!.jsonArray[1].jsonObject["data"]!!.jsonObject["routes"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        // Something else changes in the session: the plan is built again, and the mission's file is not read again.
        val other = repo.create(RecordKind.ROUTE, "ANOTHER", json("""{"version": 1, "routes": [{"id": "r-2", "name": "BESPIN", "points": []}]}"""))
        env.openSets.add(other.uuid)
        runCurrent()
        assertEquals(3, env.emitted.last().ids("routeSets").size)
        assertEquals(listOf(mission.uuid), env.reads)
        // Its routes recoloured (the record's summary, not its file): read again, as the colours are the summary's.
        repo.edit(RecordKind.MISSION, mission.uuid, data = json("""{"version": 1, "routes": [{"name": "FROM THE FILE", "color": "#FF0000"}]}"""))
        runCurrent()
        assertEquals(listOf(mission.uuid, mission.uuid), env.reads)
        // Closed in the session: forgotten, so opening it again reads it again.
        env.openSets.remove(mission.uuid)
        runCurrent()
        env.openSets.add(mission.uuid)
        runCurrent()
        assertEquals(listOf(mission.uuid, mission.uuid, mission.uuid), env.reads)
    }

    private fun slope(maxDeg: Double) = TerrainAnalysis(
        status = "success", overlay = "data:image/png;base64,AA==", bounds = listOf(listOf(34.5, -84.2), listOf(34.6, -84.0)),
        source = "local_highres_cog", resolutionM = 10.2, verticalDatum = "NAVD88",
        stats = SlopeStats(maxDeg = maxDeg, p95Deg = 3.1, areaOver6Pct = 0.0, areaOver10Pct = 0.0, areaOver15Pct = 0.0, sampleCount = 900, sampleAreaM2 = 94_000.0),
        directional = null, thresholds = SlopeThresholds(listOf(3.0, 6.0, 10.0, 15.0), Uh60Limits(6.0, 15.0, 15.0)),
        byHeading = (0..359).map { HeadingSlope(it, 2.4, if (it == 306) 5.9 else 1.8, 3.1) },
    )

    @Test
    fun `an LZ-PZ carries the slope measured over it, as the web's terrainData, while its boundary is the one it was measured for`() = sourcesTest { env ->
        val made = env.rig.diagramRepository.create(DiagramTarget(34.55, -84.11, "16S GD 66993 52949"), "LZ HAWK")
        val boundary = JsonArray(listOf(listOf(34.549, -84.111), listOf(34.551, -84.111), listOf(34.551, -84.109)).map { p -> JsonArray(p.map { JsonPrimitive(it) }) })
        env.openDiagrams.add(made.id)
        env.rig.diagrams.open(made.id)
        env.rig.diagrams.edit("Analyse") { it.copy(analysis = it.analysis.copy(detectedLZ = boundary)) }
        runCurrent()
        val key = env.analysis.boundaryKey(env.rig.diagrams.active.value!!)!!
        env.slopes.value = mapOf(made.id to SlopeState.Ready(key, slope(11.3)))
        runCurrent()
        fun terrainData() = env.emitted.last().plan["lzs"]!!.jsonArray[0].jsonObject["diagram"]!!.jsonObject["analysis"]!!.jsonObject["terrainData"]
        val terrain = terrainData()!!.jsonObject
        assertEquals(JsonPrimitive(11.3), terrain["stats"]!!.jsonObject["maxDeg"])
        val table = terrain["byHeading"]!!.jsonArray
        assertEquals(360, table.size)
        assertEquals(json("""{"heading": 306, "noseHighMaxDeg": 2.4, "noseLowMaxDeg": 5.9, "crossSlopeMaxDeg": 3.1}"""), table[306])
        // Written, closed and still open in the session: the record carries it too.
        env.rig.diagrams.flush()
        env.rig.diagrams.close()
        runCurrent()
        assertEquals(JsonPrimitive(11.3), terrainData()!!.jsonObject["stats"]!!.jsonObject["maxDeg"])
        // A boundary drawn again: the slope was measured for another, and is not briefed.
        env.rig.diagrams.open(made.id)
        env.rig.diagrams.edit("Draw") { it.copy(analysis = it.analysis.copy(detectedLZ = JsonArray(boundary.drop(1) + boundary.take(1)))) }
        runCurrent()
        assertTrue(terrainData() == null || terrainData() is JsonNull)
    }

    private fun item(uuid: String, kind: String, name: String, data: JsonObject) = PackItemView(uuid, kind, name, data)

    private fun packState(items: List<PackItemView>, people: List<JsonObject> = emptyList()) =
        PackState("p-1", PackStatus.LIVE, session = null, items = items, people = people, error = null)

    @Test
    fun `in a pack the plan is its LZ-PZs and route sets, and someone pointing on the map changes nothing`() = sourcesTest { env ->
        val hawk = json("""{"id": "lz-1", "name": "LZ HAWK"}""")
        val items = listOf(item("lz-1", "lz", "LZ HAWK", hawk), item("br-1", "brief", "OP DK BRIEF", json("{}")))
        env.packs.value = packState(items)
        runCurrent()
        val before = env.emitted.size
        val now = env.emitted.last()
        assertEquals(listOf("lz-1"), now.ids("lzs"))
        assertSame(hawk, now.plan["lzs"]!!.jsonArray[0].jsonObject["diagram"])
        assertEquals("p-1", now.pack!!.uuid)
        // A presence frame: a new state, the same items.
        env.packs.value = packState(items, people = listOf(buildJsonObject { put("user_id", 1); put("at", JsonPrimitive("34,-84")) }))
        runCurrent()
        assertEquals(before, env.emitted.size)
        // An item's document changing does change it.
        env.packs.value = packState(listOf(item("lz-1", "lz", "LZ HAWK", json("""{"id": "lz-1", "name": "LZ HAWK", "moved": true}"""))))
        runCurrent()
        assertEquals(before + 1, env.emitted.size)
    }

    @Test
    fun `threats go in only for an account with them`() = sourcesTest { env ->
        env.threats.value = listOf(ThreatEntry("t-1", Threat("SA-8", "SNGPUSAG", 34.5, -84.2, "", "", radars = emptyList())))
        runCurrent()
        assertEquals(1, env.emitted.last().threatCount)
        assertEquals("SA-8", env.emitted.last().plan["threats"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        env.allowed.value = false
        runCurrent()
        assertEquals(0, env.emitted.last().threatCount)
        assertEquals(0, env.emitted.last().plan["threats"]!!.jsonArray.size)
        assertEquals("uh60l", env.emitted.last().plan["missionAircraft"]!!.jsonPrimitive.content)
    }
}
