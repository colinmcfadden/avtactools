package app.ezpztac.data

import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackState
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramJson
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.RouteSet
import app.ezpztac.model.RouteSets
import app.ezpztac.model.ThreatEntry
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/** What a brief is compiled from, as it is now: the plan with its extras ([BriefPlans]), how many threats it holds, and the open pack (null: this session). */
data class BriefInputs(val plan: JsonObject, val threatCount: Int, val pack: BriefPackFacts?)

/** The open pack, as the brief says it: its uuid, its name, and whether it is read-only (its brief stays open to change all the same). */
data class BriefPackFacts(val uuid: String, val name: String, val readOnly: Boolean)

/**
 * Where a brief's plan comes from (docs/MISSION_BRIEF.md §1, the web's `planFromPack` and `planFromSession`): in a pack, its LZ/PZs and route sets; outside one, the
 * LZ/PZs and route sets open in this session ([OpenDiagrams], [OpenRouteSets]), the one on the map with its edits not yet written, each LZ/PZ with the slope
 * measured over it in this process while its boundary is the one it was measured for (the web's diagrams carry their runtime `terrainData`; a pack's items
 * carry none, there as here). Then the threats (none for an account without them, as the web passes none), the aircraft profiles and the mission aircraft.
 *
 * A pack's state is published for every presence frame, many a second (AGENTS.md §14), so the pack's side is narrowed to its items' documents, compared by instance,
 * before anything is built: a pointer moving compiles nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefSources(
    /** The open pack (the engine's `open`), null outside one. */
    private val packs: Flow<PackState?>,
    private val openDiagrams: OpenDiagrams,
    private val openSets: OpenRouteSets,
    private val diagrams: DiagramSession,
    private val routes: RouteSession,
    private val feed: RecordFeed,
    /** A mission's routes, read from its file without holding it ([RouteRepository.read]); null where it cannot be read here. */
    private val readSet: suspend (String) -> RouteSet?,
    private val threats: Flow<List<ThreatEntry>>,
    private val threatsAllowed: Flow<Boolean>,
    private val profiles: Flow<List<AircraftProfile>>,
    private val missionAircraft: Flow<String?>,
    /** The slope measured over each LZ/PZ in this process, by its id ([AnalysisService.slopes]): runtime only, never saved. */
    private val slopes: Flow<Map<String, SlopeState>> = flowOf(emptyMap()),
    /** Which boundary an LZ/PZ has now ([AnalysisService.boundaryKey]): a slope is briefed only while it is the one the slope was measured for. */
    private val boundaryKey: (Diagram) -> String? = { null },
) {
    /** The open pack as a brief reads it: what changes the plan, and no more. */
    private class PackSlice(val facts: BriefPackFacts, val items: List<PackItemView>)

    private fun sliceOf(state: PackState): PackSlice {
        val name = (state.session?.pack?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return PackSlice(BriefPackFacts(state.uuid, name, state.readOnly), state.items.filter { it.kind == "lz" || it.kind == "route" })
    }

    private fun sameSlice(a: PackSlice?, b: PackSlice?): Boolean {
        if (a == null || b == null) return a === b
        if (a.facts != b.facts || a.items.size != b.items.size) return false
        return a.items.indices.all { i ->
            val x = a.items[i]
            val y = b.items[i]
            x.uuid == y.uuid && x.kind == y.kind && x.name == y.name && x.data === y.data
        }
    }

    /** A mission's routes as read from its file, for the file ([fileId]) and the record's summary ([summary]: the routes' colours) they were read with. */
    private class MissionRead(val fileId: String, val summary: JsonObject, val document: JsonObject?)

    // A mission's routes read from its file, kept while its file and its colours are the same, and only while it is open: the plan is built again for every
    // change in the session, and a file can be megabytes.
    private val missionCache = ConcurrentHashMap<String, MissionRead>()

    private suspend fun missionDocument(record: LocalRecord): JsonObject? {
        val fileId = record.file?.id ?: return null
        missionCache[record.uuid]?.takeIf { it.fileId == fileId && it.summary == record.data }?.let { return it.document }
        val document = readSet(record.uuid)?.let(RouteSets::serialize)
        missionCache[record.uuid] = MissionRead(fileId, record.data, document)
        return document
    }

    private class Session(
        val lzIds: List<String>,
        val setIds: List<String>,
        val openDiagram: Diagram?,
        val openSet: RouteSet?,
        val lzs: List<LocalRecord>,
        val sets: List<LocalRecord>,
        val missions: List<LocalRecord>,
        val slopes: Map<String, SlopeState>,
    )

    private fun session(): Flow<JsonObject> {
        val records = combine(feed.observe(RecordKind.LZ), feed.observe(RecordKind.ROUTE), feed.observe(RecordKind.MISSION)) { lzs, sets, missions -> Triple(lzs, sets, missions) }
        val open = combine(openDiagrams.ids, openSets.ids, diagrams.active, routes.active) { lzIds, setIds, diagram, set -> listOf(lzIds, setIds, diagram, set) }
        return combine(open, records, slopes) { o, r, measured ->
            @Suppress("UNCHECKED_CAST")
            Session(o[0] as List<String>, o[1] as List<String>, o[2] as Diagram?, o[3] as RouteSet?, r.first, r.second, r.third, measured)
        }.mapLatest { s -> planOf(s) }
    }

    private suspend fun planOf(s: Session): JsonObject {
        val lzById = s.lzs.associateBy { it.uuid }
        val lzs = s.lzIds.mapNotNull { id ->
            val open = s.openDiagram?.takeIf { it.id == id }
            val item = when {
                open != null -> BriefPlans.SessionItem(id, open.name, DiagramJson.encode(DiagramNormalizer.serialize(open)))
                else -> lzById[id]?.let { BriefPlans.SessionItem(id, it.name, it.data) }
            }
            val slope = (s.slopes[id] as? SlopeState.Ready)?.takeIf { ready ->
                item != null && boundaryKey(open ?: DiagramNormalizer.normalize(item.document)) == ready.boundaryKey
            }
            if (item != null && slope != null) item.copy(document = withSlope(item.document, slope.analysis)) else item
        }
        val setById = s.sets.associateBy { it.uuid }
        val missionById = s.missions.associateBy { it.uuid }
        missionCache.keys.retainAll(s.setIds.toSet())
        val sets = s.setIds.mapNotNull { id ->
            val open = s.openSet?.takeIf { it.id == id }
            when {
                open != null -> BriefPlans.SessionItem(id, open.name, RouteSets.serialize(open))
                setById[id] != null -> setById.getValue(id).let { BriefPlans.SessionItem(id, it.name, it.data) }
                missionById[id] != null -> missionById.getValue(id).let { record -> missionDocument(record)?.let { BriefPlans.SessionItem(id, record.name, it) } }
                else -> null
            }
        }
        return BriefPlans.fromSession(lzs, sets)
    }

    /** [document] with the slope measured over it as its analysis's `terrainData`, as the web's runtime one: what the brief's slope call reads, and no more. */
    private fun withSlope(document: JsonObject, slope: TerrainAnalysis): JsonObject {
        val terrain = buildJsonObject {
            putJsonObject("stats") { put("maxDeg", slope.stats.maxDeg) }
            slope.byHeading?.let { table ->
                putJsonArray("byHeading") {
                    table.forEach { at ->
                        addJsonObject {
                            put("heading", at.heading)
                            put("noseHighMaxDeg", at.noseHighMaxDeg)
                            put("noseLowMaxDeg", at.noseLowMaxDeg)
                            put("crossSlopeMaxDeg", at.crossSlopeMaxDeg)
                        }
                    }
                }
            }
        }
        val analysis = document["analysis"] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(document + ("analysis" to JsonObject(analysis + ("terrainData" to terrain))))
    }

    /** What a brief is compiled from now, and again whenever any of it changes. */
    val inputs: Flow<BriefInputs> = run {
        val pack = packs.map { state -> state?.let(::sliceOf) }.distinctUntilChanged(::sameSlice)
        val plan = pack.flatMapLatest { slice ->
            if (slice != null) flowOf(BriefPlans.fromPack(slice.facts.name, slice.items) to slice.facts) else session().map { it to null }
        }
        val shownThreats = combine(threats, threatsAllowed) { list, allowed -> if (allowed) list else emptyList() }
        val aircraft = combine(profiles, missionAircraft) { list, slug -> list to slug }
        combine(plan, shownThreats, aircraft) { (base, facts), list, (profileList, slug) ->
            BriefInputs(BriefPlans.withExtras(base, list, profileList, slug), list.size, facts)
        }
    }
}
