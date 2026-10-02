package app.ezpztac.data

import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
import app.ezpztac.network.FieldAnalysis
import app.ezpztac.network.NetworkException
import app.ezpztac.network.SlopeStats
import app.ezpztac.network.SlopeThresholds
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.network.Uh60Limits
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisServiceTest {
    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")
    private val found = listOf(listOf(34.71, -84.09), listOf(34.71, -84.01), listOf(34.79, -84.01), listOf(34.79, -84.09))

    private fun slopeResult() = TerrainAnalysis(
        status = "success", overlay = "data:image/png;base64,AA==", bounds = listOf(listOf(34.7, -84.1), listOf(34.8, -84.0)),
        source = "local_highres_cog", resolutionM = 10.2, verticalDatum = "NAVD88",
        stats = SlopeStats(maxDeg = 4.2, p95Deg = 3.1, areaOver6Pct = 0.0, areaOver10Pct = 0.0, areaOver15Pct = 0.0, sampleCount = 900, sampleAreaM2 = 94_000.0),
        directional = null, thresholds = SlopeThresholds(listOf(3.0, 6.0, 10.0, 15.0), Uh60Limits(6.0, 15.0, 15.0)),
    )

    private class FakeTerrain(
        val find: suspend (LatLon) -> FieldAnalysis,
        val measure: suspend (List<LatLon>) -> TerrainAnalysis,
    ) : TerrainApi {
        val fieldCalls = mutableListOf<LatLon>()
        val slopeCalls = mutableListOf<List<LatLon>>()
        val headings = mutableListOf<Double?>()
        override suspend fun analyzeField(at: LatLon): FieldAnalysis { fieldCalls += at; return find(at) }
        override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis {
            slopeCalls += polygon; headings += landingHeadingDeg; return measure(polygon)
        }
    }

    private inner class Rig(scope: TestScope, find: (suspend (LatLon) -> FieldAnalysis)? = null, measure: (suspend (List<LatLon>) -> TerrainAnalysis)? = null) {
        val device = Device("A", FakeServer())
        val scheduler = RecordingScheduler()
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = DiagramSession(repository, scope.backgroundScope)
        val api = FakeTerrain(
            find ?: { FieldAnalysis("success", found, "4050", "Field detected") },
            measure ?: { slopeResult() },
        )
        // Not backgroundScope: advanceUntilIdle leaves a background scope's work alone, and the service's work is what a test waits for.
        val service = AnalysisService(
            api, session, repository, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler),
        )

        suspend fun targeted(name: String = "LZ HAWK"): String = repository.create(target, name).id
        suspend fun draft(): String {
            val id = repository.create(target, "draft").id
            session.update(id) { it.copy(target = null, status = DiagramStatus.DRAFT) }
            return id
        }
        suspend fun stored(id: String) = repository.open(id)!!
    }

    // -- The ordinary analysis ------------------------------------------------------------------------------------

    @Test
    fun `an analysis finds the area, makes the diagram analysed, and measures the slope`() = runTest {
        val r = Rig(this)
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()

        assertEquals(AnalysisStatus.Idle, r.service.status.value)
        val d = r.session.active.value!!
        assertEquals(DiagramStatus.ANALYZED, d.status)
        assertEquals(listOf(LatLon(34.783817, -84.08219)), r.api.fieldCalls)
        assertEquals(JsonPrimitive("4050"), d.analysis.gridElevation)
        assertEquals(JsonPrimitive("34° 47' 1.74\" N  84° 4' 55.88\" W"), d.analysis.latLong)           // as the web writes it
        assertEquals(JsonNull, d.analysis.customLZ)
        val boundary = d.analysis.detectedLZ.jsonArray
        assertEquals(4, boundary.size)
        assertEquals(34.71, boundary[0].jsonArray[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("Field detected", d.analysis.results.jsonObject["message"]!!.jsonPrimitive.content)   // the server's answer is kept whole
        assertEquals(found.map { LatLon(it[0], it[1]) }, r.api.slopeCalls.single())
        assertEquals(listOf<Double?>(null), r.api.headings)                                               // the web sends no heading
        val slope = r.service.slopes.value[id] as SlopeState.Ready
        assertEquals(4.2, slope.analysis.stats.maxDeg, 0.0)
    }

    @Test
    fun `the standard doghouses are made once, with ids from the diagram`() = runTest {
        val r = Rig(this)
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        val first = r.session.active.value!!.graphics.doghouses
        assertEquals(listOf("[SP1]", "[RP1]"), first.map { it.jsonObject["id_val"]!!.jsonPrimitive.content })
        assertEquals("$id-sp1", first[0].jsonObject["id"]!!.jsonPrimitive.content)

        // The person edits one; a second analysis must not make them again or undo the edit.
        r.session.edit("Doghouse") { d ->
            d.copy(graphics = d.graphics.copy(doghouses = d.graphics.doghouses.map { h ->
                if (h.jsonObject["id_val"]!!.jsonPrimitive.content == "[SP1]") JsonObject(h.jsonObject + ("heading" to JsonPrimitive("270°"))) else h
            }))
        }
        r.service.analyze(id)
        advanceUntilIdle()
        val second = r.session.active.value!!.graphics.doghouses
        assertEquals(2, second.size)
        assertEquals("270°", second[0].jsonObject["heading"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a boundary the person drew becomes the analysis boundary, and the drawn one is cleared`() = runTest {
        val r = Rig(this)
        val id = r.targeted()
        val drawn = JsonArray(listOf(listOf(34.7, -84.1), listOf(34.7, -84.0), listOf(34.8, -84.0)).map { p -> JsonArray(p.map(::JsonPrimitive)) })
        r.session.update(id) { it.copy(analysis = it.analysis.copy(customLZ = drawn)) }
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        val d = r.session.active.value!!
        assertEquals(drawn, d.analysis.detectedLZ)
        assertEquals(JsonNull, d.analysis.customLZ)
        assertEquals(listOf(LatLon(34.7, -84.1), LatLon(34.7, -84.0), LatLon(34.8, -84.0)), r.api.slopeCalls.single())
        assertEquals(1, r.api.fieldCalls.size)                                                           // the elevation still comes from the server
    }

    @Test
    fun `a drawn line of two points is not a boundary`() = runTest {
        val r = Rig(this)
        val id = r.targeted()
        val line = JsonArray(listOf(listOf(34.7, -84.1), listOf(34.7, -84.0)).map { p -> JsonArray(p.map(::JsonPrimitive)) })
        r.session.update(id) { it.copy(analysis = it.analysis.copy(customLZ = line)) }
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals(4, r.session.active.value!!.analysis.detectedLZ.jsonArray.size)                      // the model's area
    }

    @Test
    fun `an analysis is not an edit to take back, and undoing an earlier edit keeps it`() = runTest {
        val r = Rig(this)
        val id = r.targeted("v0")
        r.session.open(id)
        r.session.edit("Rename") { it.copy(name = "v1") }
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals(1, r.session.undoDepth.value)
        r.session.undo()
        assertEquals("v0", r.session.active.value!!.name)
        assertEquals(DiagramStatus.ANALYZED, r.session.active.value!!.status)
    }

    @Test
    fun `the result is saved and the server is to be told`() = runTest {
        val r = Rig(this)
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        r.session.flush()
        assertEquals(DiagramStatus.ANALYZED, r.stored(id).status)
        assertTrue(r.scheduler.requested > 1)
    }

    // -- When it does not work --------------------------------------------------------------------------------------

    @Test
    fun `a diagram with no target is refused in words, and the server is not asked`() = runTest {
        val r = Rig(this)
        val id = r.draft()
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Failed(id, "Set a target on the map before analysing."), r.service.status.value)
        assertEquals(emptyList<LatLon>(), r.api.fieldCalls)
    }

    @Test
    fun `no area at the point leaves the diagram as it was, and can be tried again`() = runTest {
        var answer: suspend (LatLon) -> FieldAnalysis = { throw ApiException(400, null, "No distinct field found at this point") }
        val r = Rig(this, find = { answer(it) })
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Failed(id, "No distinct landing area was found at this point. Try a different target."), r.service.status.value)
        assertEquals(DiagramStatus.TARGETED, r.session.active.value!!.status)
        assertEquals(emptyList<LatLon>(), r.api.slopeCalls)

        r.service.dismiss()
        assertEquals(AnalysisStatus.Idle, r.service.status.value)
        answer = { FieldAnalysis("success", found, "4050", "Field detected") }
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals(DiagramStatus.ANALYZED, r.session.active.value!!.status)
    }

    @Test
    fun `no signal says so, and the diagram is safe`() = runTest {
        val r = Rig(this, find = { throw NetworkException("timeout", null, requestMayHaveBeenSent = true) })
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        val failed = r.service.status.value as AnalysisStatus.Failed
        assertTrue(failed.message.startsWith("There is no connection"))
        assertEquals(DiagramStatus.TARGETED, r.session.active.value!!.status)
    }

    @Test
    fun `a failure of the service is in words, not the server's internals`() = runTest {
        val r = Rig(this, find = { throw ApiException(500, null, "'NoneType' object has no attribute 'xy'") })
        val id = r.targeted()
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals("The analysis service did not answer. Try again in a moment.", (r.service.status.value as AnalysisStatus.Failed).message)
    }

    @Test
    fun `a failure that is not the server's is caught too`() = runTest {
        val r = Rig(this, find = { error("boom") })
        val id = r.targeted()
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals("The analysis could not be completed.", (r.service.status.value as AnalysisStatus.Failed).message)
    }

    @Test
    fun `a slope that cannot be measured does not undo the analysis`() = runTest {
        val r = Rig(this, measure = { throw ApiException(502, null, "No configured terrain source covers this LZ") })
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Idle, r.service.status.value)
        assertEquals(DiagramStatus.ANALYZED, r.session.active.value!!.status)
        assertEquals(SlopeState.Unavailable("No terrain data covers this landing zone."), r.service.slopes.value[id])
    }

    // -- A result belongs to the diagram it was asked for -------------------------------------------------------------------

    @Test
    fun `an answer that arrives after another diagram was opened goes to the diagram it was for`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, find = { gate.await(); FieldAnalysis("success", found, "4050", "Field detected") })
        val a = r.targeted("A")
        val b = r.targeted("B")
        r.session.open(a)
        r.service.analyze(a)
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Running(a, AnalysisStatus.Running.Stage.FINDING_AREA), r.service.status.value)

        r.session.open(b)                                                                                 // the person moves on while the server works
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(b, r.session.active.value!!.id)
        assertEquals(DiagramStatus.TARGETED, r.session.active.value!!.status)                              // B is untouched
        assertEquals(DiagramStatus.ANALYZED, r.stored(a).status)                                           // A has its analysis
        assertEquals(DiagramStatus.TARGETED, r.stored(b).status)
        assertTrue(r.service.slopes.value[a] is SlopeState.Ready)
        assertNull(r.service.slopes.value[b])
    }

    @Test
    fun `a diagram deleted while it is analysed gets nothing, and nothing comes back`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, find = { gate.await(); FieldAnalysis("success", found, "4050", "Field detected") })
        val a = r.targeted("A")
        r.service.analyze(a)
        advanceUntilIdle()
        r.repository.delete(a)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Idle, r.service.status.value)
        assertEquals(emptyList<String>(), r.device.names(app.ezpztac.sync.RecordKind.LZ))
        assertEquals(emptyList<List<LatLon>>(), r.api.slopeCalls)
    }

    // -- Running and stopping ---------------------------------------------------------------------------------------------

    @Test
    fun `only one analysis runs at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, find = { gate.await(); FieldAnalysis("success", found, "4050", "Field detected") })
        val a = r.targeted("A")
        val b = r.targeted("B")
        r.service.analyze(a)
        r.service.analyze(b)                                                                              // ignored: the server is busy with A
        advanceUntilIdle()
        assertEquals(1, r.api.fieldCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DiagramStatus.TARGETED, r.stored(b).status)
    }

    @Test
    fun `stopping waits no longer, abandons the request and leaves the diagram as it was`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val r = Rig(this, find = { try { gate.await(); FieldAnalysis("success", found, "4050", "Field detected") } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e } })
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        r.service.cancel()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(AnalysisStatus.Idle, r.service.status.value)
        assertEquals(DiagramStatus.TARGETED, r.session.active.value!!.status)
        r.service.analyze(id)                                                                             // and a new one can start
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DiagramStatus.ANALYZED, r.session.active.value!!.status)
    }

    @Test
    fun `while the slope is measured the status says so, and the analysis is already in the diagram`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, measure = { gate.await(); slopeResult() })
        val id = r.targeted()
        r.session.open(id)
        r.service.analyze(id)
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Running(id, AnalysisStatus.Running.Stage.MEASURING_SLOPE), r.service.status.value)
        assertEquals(DiagramStatus.ANALYZED, r.session.active.value!!.status)
        assertEquals(SlopeState.Loading, r.service.slopes.value[id])
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Idle, r.service.status.value)
    }

    @Test
    fun `asking for the slope twice while it is being measured asks the server once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, measure = { gate.await(); slopeResult() })
        val id = r.targeted()
        r.session.update(id) { DiagramOps.afterAnalysis(it, null).let { d -> d.copy(analysis = d.analysis.copy(detectedLZ = JsonArray(found.map { p -> JsonArray(p.map(::JsonPrimitive)) }))) } }
        r.session.open(id)
        r.service.ensureSlope(r.session.active.value!!)
        r.service.ensureSlope(r.session.active.value!!)
        advanceUntilIdle()
        assertEquals(1, r.api.slopeCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.service.slopes.value[id] is SlopeState.Ready)
    }

    // -- The slope on its own ------------------------------------------------------------------------------------------------

    private suspend fun Rig.analysed(): String {
        val id = targeted()
        session.open(id)
        service.analyze(id)
        return id
    }

    @Test
    fun `opening an analysed diagram measures its slope once, and again only when the boundary changes`() = runTest {
        val r = Rig(this)
        val id = r.analysed()
        advanceUntilIdle()
        assertEquals(1, r.api.slopeCalls.size)

        r.service.ensureSlope(r.session.active.value!!)
        advanceUntilIdle()
        assertEquals(1, r.api.slopeCalls.size)                                                            // already known for this boundary

        val moved = JsonArray(listOf(listOf(34.7, -84.1), listOf(34.7, -84.0), listOf(34.8, -84.0)).map { p -> JsonArray(p.map(::JsonPrimitive)) })
        r.session.edit("Boundary") { it.copy(analysis = it.analysis.copy(detectedLZ = moved)) }
        r.service.ensureSlope(r.session.active.value!!)
        advanceUntilIdle()
        assertEquals(2, r.api.slopeCalls.size)
        assertEquals(3, (r.service.slopes.value[id] as SlopeState.Ready).let { r.api.slopeCalls.last().size })
    }

    @Test
    fun `a diagram with no boundary, or a line, has no slope to measure`() = runTest {
        val r = Rig(this)
        val id = r.targeted()
        r.session.open(id)
        r.service.ensureSlope(r.session.active.value!!)
        val line = JsonArray(listOf(listOf(34.7, -84.1), listOf(34.7, -84.0)).map { p -> JsonArray(p.map(::JsonPrimitive)) })
        r.service.ensureSlope(r.session.active.value!!.let { it.copy(analysis = it.analysis.copy(detectedLZ = line)) })
        advanceUntilIdle()
        assertEquals(emptyList<List<LatLon>>(), r.api.slopeCalls)
        assertFalse(r.service.slopes.value.containsKey(id))
    }

    @Test
    fun `a slope that failed is tried again the next time the diagram is opened`() = runTest {
        var failing = true
        val r = Rig(this, measure = { if (failing) throw NetworkException("no route", null, requestMayHaveBeenSent = false) else slopeResult() })
        val id = r.analysed()
        advanceUntilIdle()
        assertTrue(r.service.slopes.value[id] is SlopeState.Unavailable)
        failing = false
        r.service.ensureSlope(r.session.active.value!!)
        advanceUntilIdle()
        assertTrue(r.service.slopes.value[id] is SlopeState.Ready)
    }
}
