package app.ezpztac.brief

import app.ezpztac.brief.BriefText.airspeedText
import app.ezpztac.brief.BriefText.altitudeText
import app.ezpztac.brief.BriefText.clockText
import app.ezpztac.brief.BriefText.compassOf
import app.ezpztac.brief.BriefText.dateText
import app.ezpztac.brief.BriefText.durationText
import app.ezpztac.brief.BriefText.feetText
import app.ezpztac.brief.BriefText.fuelText
import app.ezpztac.brief.BriefText.headingText
import app.ezpztac.brief.BriefText.instantMinuteText
import app.ezpztac.brief.BriefText.minuteText
import app.ezpztac.brief.BriefText.nmText
import app.ezpztac.brief.BriefText.percentText
import app.ezpztac.brief.BriefText.plural
import app.ezpztac.brief.BriefText.pointName
import app.ezpztac.brief.BriefText.wallOf
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.Doghouses
import app.ezpztac.model.JsNumber
import app.ezpztac.model.PlanPoint
import app.ezpztac.model.PlanResult
import app.ezpztac.model.RoutePoint
import app.ezpztac.planning.LzSummary
import app.ezpztac.planning.RouteCalc
import app.ezpztac.planning.RoutePlans
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * compileBrief: a format, the person's answers and the plan in, everything the brief says out (docs/MISSION_BRIEF.md §2.3).
 *
 * The one place a brief's content is decided: the PDF and the sandtable only lay out what this returns, so a step says the same thing on
 * the sandtable as in the PDF. A port of the web's `feature/brief/compile.js`, function by function, held to every case of
 * `contracts/fixtures/brief/compile.json` strictly (null kept apart from absent, numbers as JavaScript writes them, every object's keys in
 * JavaScript's order). Pure and deterministic: no clock (the generation time is an input), no network, no random ids; with the web's one
 * exception, a plan with no dated route and no readable `generatedAt`, whose clock times the route planner puts on the device's today.
 *
 * The inputs are JSON as the web passes them, because they are the documents the app already holds (a Library record's or a pack item's
 * data), and the plan is read with the web's loose rules ([Js]); the model is JSON out, because it is what `POST /api/brief/pdf` takes and
 * what the sandtable reads. The plan's diagrams and routes are read with the ports the app already has: [DiagramNormalizer],
 * [RoutePlans] and [RouteCalc], [Doghouses.landingHeadingOf], [LzSummary.slopeCall] and [MgrsConverter].
 *
 * **Not like the web**, each where the web reads what no client writes, or depends on the device:
 * - a route with a point that is not an object with a numeric `lat` and `lon`, or a plan the planner's types cannot read, is not briefed, so a
 *   flow naming it says it is gone (the web would brief it with NaN for distances and times);
 * - a plan value given as text (`"120"`) is read as its number (the web would concatenate it, or say nothing), and a point whose id is
 *   something with empty text (`[]`) is no named point (the web would plan it);
 * - on a day the clocks change, the web's route planner counts clock times through the device's zone, so a time in the hour skipped or repeated
 *   comes out an hour off there, and differently on each device; here a plan's wall-clock times are counted as they are, in any zone.
 */
public object CompileBrief {
    public const val MODEL_FORMAT: String = "ezpz-brief-model"
    public const val MODEL_VERSION: Int = 1

    /** How a write-in line is shown where a brief is read as text (the sandtable, a preview). */
    public const val WRITE_IN: String = "________"

    /**
     * Compiles a brief.
     *
     * @param format the format ([BriefFormats.format])
     * @param document the person's brief (docs/MISSION_BRIEF.md §2.2): flow, constants, answers, scripts, options, rallyPoints, include
     * @param plan `{ name, lzs: [{id, name?, diagram}], routeSets: [{id, name, data}], aircraftProfiles, missionAircraft, threats }`
     * @param context `{ generatedAt (ISO), utcOffsetMinutes, weather: {departure, arrival, enroute, stops}, notams }`
     * @return the BriefModel
     */
    public fun compile(format: BriefFormat, document: JsonElement? = null, plan: JsonElement? = null, context: JsonElement? = null): JsonObject =
        Compiler(format, document as? JsonObject ?: EMPTY, plan as? JsonObject ?: EMPTY, context as? JsonObject ?: EMPTY).compile()

    /** A script's or an item's parts as plain text, a write-in as a line ([WRITE_IN]). */
    public fun partsText(parts: JsonArray): String = parts.joinToString("") { part ->
        val o = part as? JsonObject
        if (o != null && o.containsKey("writeIn")) WRITE_IN else Js.present(o?.get("text"))?.let(Js::text).orEmpty()   // join() says nothing for null
    }

    /** A step's script as plain sentences, a write-in as a line: for the sandtable and anything that reads text. */
    public fun scriptText(step: JsonObject): List<String> = (step["script"] as? JsonArray).orEmpty().map { partsText(it as? JsonArray ?: JsonArray(emptyList())) }

    internal val EMPTY = JsonObject(emptyMap())
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// The plan, as the brief reads it.

/** An LZ/PZ of the plan. */
internal class StopItem(val ref: String, val id: String, val name: String, val diagram: Diagram, val target: DiagramTarget?)

/** A named (AMPS) point of a route, as the route holds it: what `===` compares and what a rally point names. */
internal class NamedPoint(val raw: JsonObject, val point: RoutePoint)

/** A route of the plan with the planner's results. [named] and [result]'s points are the same points, in the same order. */
internal class RouteItem(
    val ref: String,
    val id: String,
    val name: String,
    val raw: JsonObject,
    val points: List<RoutePoint>,
    /** Every point of the route as it holds it, shaping points too, in its order: what a strip chart is cut along. */
    val every: List<NamedPoint>,
    val named: List<NamedPoint>,
    val result: PlanResult,
)

/** An entry of the flow: a new object for each place the flow names an item, as the web's spreads make one, so two are never the same. */
internal sealed class Entry {
    abstract val ref: String
}

internal class StopEntry(val item: StopItem, val role: String) : Entry() {
    override val ref: String get() = item.ref
    val name: String get() = item.name
    val target: DiagramTarget? get() = item.target
    val diagram: Diagram get() = item.diagram
}

internal class RouteEntry(val item: RouteItem) : Entry() {
    override val ref: String get() = item.ref
    val name: String get() = item.name
    val points: List<PlanPoint> get() = item.result.points
}

internal class GoneEntry(override val ref: String) : Entry()

/** A part of a sentence: text, or a line to write on with what it is for. */
private sealed class Part {
    class Text(var text: String) : Part()
    class WriteIn(val name: String, val label: String?) : Part()
}

private class SectionAt(val section: BriefSection, val paragraph: BriefParagraph)

private class Group(val step: String?, val repeat: String?, val sections: MutableList<SectionAt>)

private class Planned(val group: Group, val entry: Entry?)

private class RoutePointHit(val point: PlanPoint, val index: Int, val d: Double)

private class Compiler(
    private val format: BriefFormat,
    private val doc: JsonObject,
    private val plan: JsonObject,
    private val context: JsonObject,
) {
    private val template = format.template
    private val bindings = format.bindings
    private val catalog = format.constants
    private val standards = format.standards
    private val offset: Double = Js.num(context["utcOffsetMinutes"]) ?: 0.0
    private val problems = ArrayList<JsonObject>()
    private val answers: JsonObject = doc["answers"] as? JsonObject ?: CompileBrief.EMPTY

    private fun problem(ref: String, message: String) {
        problems += buildJsonObject {
            put("ref", ref)
            put("message", message)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Reading the plan.

    private fun readStop(entry: JsonObject): StopItem {
        val source = entry["diagram"]?.takeIf(Js::truthy) ?: CompileBrief.EMPTY
        val diagram = DiagramNormalizer.normalize(source, DiagramNormalizer.Options(createdAt = "", updatedAt = ""), FIXED_ENVIRONMENT)
        // The diagram's name as it was saved (the normaliser keeps only text; the web's keeps whatever it is, and says it with String()).
        val saved = Js.present(entry["name"]) ?: Js.present(Js.prop(source, "name")) ?: JsonPrimitive("")
        val name = Js.trim(Js.text(saved)).ifEmpty { "LZ/PZ" }
        val id = Js.text(entry["id"])
        return StopItem(ref = "lz:$id", id = id, name = name, diagram = diagram, target = diagram.target)
    }

    /** A route's point as the planner reads it; null for one that is not an object with a numeric position. */
    private fun pointOf(element: JsonElement): NamedPoint? {
        val o = element as? JsonObject ?: return null
        val lat = Js.num(o["lat"]) ?: return null
        val lon = Js.num(o["lon"]) ?: return null
        val point = RoutePoint(
            // `p.id` is what the planner and the per-point values go by when it is truthy; the per-point values are keyed by its text.
            id = o["id"]?.takeIf(Js::truthy)?.let { Js.text(it) },
            uiId = Js.stringOf(o["uiId"]),
            lat = lat,
            lon = lon,
            kind = Js.stringOf(o["kind"]),
            ptType = Js.stringOf(o["ptType"]),
            name = Js.stringOf(o["name"]),
            chartElevationFt = Js.num(o["chartElevationFt"]),
        )
        return NamedPoint(o, point)
    }

    private fun readRoute(setId: String, raw: JsonObject, missionDate: String?): RouteItem? {
        val parsed = (raw["points"] as JsonArray).map { pointOf(it) ?: return null }
        val points = parsed.map { it.point }
        val elevations = (raw["elevations"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> Js.num(value)?.let { key to it } }.toMap()
        val ensured = try {
            RoutePlans.ensure(points, (raw["plan"] as? JsonObject)?.let(::withoutNullPerPoint)?.let(::withAirspeedKinds))
        } catch (_: IllegalArgumentException) {                                             // SerializationException is one
            return null
        }
        val routePlan = ensured.copy(date = rolledDate(ensured.date.ifEmpty { missionDate ?: "" }))
        val result = RouteCalc.computeRoutePlan(points, routePlan, elevations)
        val id = Js.text(raw["id"])
        return RouteItem(
            ref = "route:$setId/$id",
            id = id,
            name = Js.trim(Js.text(raw["name"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))).ifEmpty { "ROUTE" },
            raw = raw,
            points = points,
            every = parsed,
            // The planner's own filter, so these stay the plan's points one for one.
            named = parsed.filter { it.point.kind != RoutePoint.KIND_SHAPING && !it.point.id.isNullOrEmpty() },
            result = result,
        )
    }

    /** A point's values that are null are none, as the web reads `plan.perPoint?.[id]` (a mission pack leaves one for a key that went). */
    private fun withoutNullPerPoint(plan: JsonObject): JsonObject {
        val perPoint = plan["perPoint"] as? JsonObject ?: return plan
        if (perPoint.values.none { it is JsonNull }) return plan
        return JsonObject(LinkedHashMap(plan).apply { put("perPoint", JsonObject(perPoint.filterValues { it !is JsonNull })) })
    }

    /**
     * An airspeed with no `type` (or a null one) keeps none: the web plans it as true airspeed and says it as ground speed (`kt GS`), and the
     * planner's type would otherwise read it as ground speed. Text that is no type does both, as `undefined` does there.
     */
    private fun withAirspeedKinds(plan: JsonObject): JsonObject {
        fun kinded(airspeed: JsonElement?): JsonElement? =
            if (airspeed is JsonObject && Js.present(airspeed["type"]) == null) JsonObject(LinkedHashMap(airspeed).apply { put("type", JsonPrimitive("")) }) else null
        val fields = LinkedHashMap(plan)
        kinded(plan["airspeed"])?.let { fields["airspeed"] = it }
        (plan["perPoint"] as? JsonObject)?.let { perPoint ->
            val entries = LinkedHashMap(perPoint)
            perPoint.forEach { (id, entry) -> kinded(Js.prop(entry, "airspeed"))?.let { entries[id] = JsonObject(LinkedHashMap(entry as JsonObject).apply { put("airspeed", it) }) } }
            fields["perPoint"] = JsonObject(entries)
        }
        return JsonObject(fields)
    }

    /** A plan's date as the web's `new Date("YYYY-MM-DDT00:00:00")` reads it: a day past its month's end rolls over (`2026-02-30` is 2 March). */
    private fun rolledDate(date: String): String {
        val m = ISO_DATE_PARTS.matchEntire(date) ?: return date
        val (y, mo, d) = m.destructured
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) return date
        return java.time.LocalDate.of(y.toInt(), mo.toInt(), 1).plusDays(d.toLong() - 1).toString()
    }

    private fun readRoutes(set: JsonObject, missionDate: String?): List<RouteItem> {
        val setId = Js.text(set["id"])
        return (Js.path(set, "data", "routes") as? JsonArray).orEmpty()
            .filter { it is JsonObject && it["id"] != null && it["points"] is JsonArray }
            .mapNotNull { readRoute(setId, it as JsonObject, missionDate) }
    }

    /** The local date (YYYY-MM-DD) of an instant at an offset. */
    private fun localDateOf(iso: JsonElement?, offsetMinutes: Double): String? {
        val text = if (iso == null) null else Js.text(iso)
        val ms = BriefText.parseDate(text) ?: return null
        return BriefText.isoString(ms + offsetMinutes * 60000)?.let { Js.slice(it, 0, 10) }
    }

    private fun defaultRole(name: String): String {
        val text = Js.trim(name).uppercase()
        fun starts(word: String) = text.startsWith(word) && text.getOrNull(word.length)?.let(::isWordChar) != true
        return when {
            starts("PZ") -> "PZ"
            starts("FARP") -> "FARP"
            starts("HA") || starts("HOLDING") -> "HA"
            starts("LAAGER") -> "LAAGER"
            else -> "LZ"
        }
    }

    /** JavaScript's `\w`, which is ASCII whatever the platform's regular expressions take a word character to be. */
    private fun isWordChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

    private fun metresBetween(lat: Double, lon: Double, target: DiagramTarget): Double = Sphere.metresBetween(lat, lon, target.lat, target.lon)

    /**
     * The flow: the person's order if they gave one, else each route followed by the LZ/PZs at its points (in the order it reaches them),
     * then any LZ/PZ no route reaches. An entry that names nothing in the plan stays, marked gone, so setup can say so.
     */
    private fun resolveFlow(stops: List<StopItem>, routes: List<RouteItem>): List<Entry> {
        val documentFlow = doc["flow"] as? JsonArray
        val byRef = LinkedHashMap<String, Any>()
        stops.forEach { byRef[it.ref] = it }
        routes.forEach { byRef[it.ref] = it }
        if (documentFlow != null && documentFlow.isNotEmpty()) {
            return documentFlow.filter { it is JsonObject && Js.isString(it["ref"]) }.map { element ->
                val f = element as JsonObject
                val ref = Js.stringOf(f["ref"])!!
                when (val item = byRef[ref]) {
                    null -> {
                        problem(ref, "This item is no longer in the plan.")
                        GoneEntry(ref)
                    }
                    is RouteItem -> RouteEntry(item)
                    else -> {
                        val stop = item as StopItem
                        val asked = Js.stringOf(f["role"])
                        StopEntry(stop, if (asked != null && asked in BriefFormats.ROLES) asked else defaultRole(stop.name))
                    }
                }
            }
        }
        val flow = ArrayList<Entry>()
        val used = HashSet<String>()
        routes.forEach { route ->
            flow += RouteEntry(route)
            val reached = ArrayList<Pair<StopItem, Int>>()
            stops.forEach { stop ->
                val target = stop.target
                if (stop.ref in used || target == null) return@forEach
                val index = route.named.indexOfFirst { metresBetween(it.point.lat, it.point.lon, target) <= STOP_MATCH_M }
                if (index >= 0) reached += stop to index
            }
            reached.sortedBy { it.second }.forEach { (stop) ->
                used += stop.ref
                flow += StopEntry(stop, defaultRole(stop.name))
            }
        }
        stops.forEach { stop -> if (stop.ref !in used) flow += StopEntry(stop, defaultRole(stop.name)) }
        return flow
    }

    /** The named point of a route nearest a stop's target, within reach, with its plan row; else null. */
    private fun routePointAt(route: RouteEntry, target: DiagramTarget?): RoutePointHit? {
        if (target == null || route.points.isEmpty()) return null
        var best: RoutePointHit? = null
        route.points.forEachIndexed { index, p ->
            val d = metresBetween(p.lat, p.lon, target)
            if (d <= STOP_MATCH_M && (best == null || d < best.d)) best = RoutePointHit(p, index, d)
        }
        return best
    }

    /** The route before [entry] in [flow], if any. */
    private fun routeBefore(flow: List<Entry>, entry: Entry): RouteEntry? =
        flow.subList(0, flow.indexOfFirst { it === entry }.coerceAtLeast(0)).lastOrNull { it is RouteEntry } as RouteEntry?

    // ------------------------------------------------------------------------------------------------------------------------------
    // Bindings: every value bindings.json promises, worked out once for the brief, once per stop and once per route. A value is text, a
    // list of text, or null.

    private fun weatherText(report: JsonElement?): String? {
        if (report !is JsonObject) return null
        val station = Js.stringOf(report["station_id"])?.let(Js::trim) ?: ""
        if (station.isEmpty() || station == "TIMEOUT") return null
        val raw = listOf(report["raw_text"], report["raw_taf"], report["raw_metar"])
            .firstNotNullOfOrNull { r -> Js.stringOf(r)?.takeIf { Js.trim(it).isNotEmpty() } } ?: return null
        return if (Js.isText(report["kind"], "forecast")) "Forecast ${Js.trim(raw)}" else Js.trim(raw)
    }

    private fun notamsText(notams: JsonElement?): String? {
        if (Js.isString(notams)) return "NOTAMs could not be searched: check them before flight."
        if (notams !is JsonObject) return null
        val kinds = Js.orderedKeys(notams.keys).mapNotNull { kind ->
            val list = notams[kind] as? JsonArray
            if (kind != "Clear" && list != null && list.isNotEmpty()) kind to list else null
        }
        if (kinds.isEmpty()) return "None in the area."
        val total = kinds.sumOf { it.second.size }
        return "${plural(total, "NOTAM", "NOTAMs")}: ${kinds.joinToString(", ") { (kind, list) -> "${list.size} ${kind.lowercase()}" }}."
    }

    private class Run(val value: String?, val from: PlanPoint, var to: PlanPoint)

    /** A route's legs grouped where a value changes: "120 KIAS (3MILE to S-EP), 100 KIAS (S-EP to RP1)". */
    private fun changesText(points: List<PlanPoint>, valueOf: (PlanPoint) -> String?): String? {
        val runs = ArrayList<Run>()
        points.forEachIndexed { i, p ->
            if (i == 0) return@forEachIndexed
            val value = valueOf(p)
            val last = runs.lastOrNull()
            if (last != null && last.value == value) last.to = p else runs += Run(value, points[i - 1], p)
        }
        val said = runs.filter { it.value != null }
        if (said.isEmpty()) return null
        if (said.size == 1 && runs.size == 1) return said[0].value
        return said.joinToString(", ") { "${it.value} (${pointName(it.from.name, "start")} to ${pointName(it.to.name, "end")})" }
    }

    private fun altitudeOf(p: PlanPoint): String? = altitudeText(p.ref, p.value)

    /** Leaving along a route from its point [from] (its start unless said): name, takeoff, heading, altitude, airspeed. */
    private fun departurePlanText(route: RouteEntry?, from: Int = 0): String? {
        val first = route?.points?.getOrNull(from) ?: return null
        val second = route.points.getOrNull(from + 1) ?: return null
        val parts = arrayListOf(if (from > 0) "${route.name} from ${pointName(first.name, "here")}" else route.name)
        clockText(wallOf(first.clockTime))?.let { parts += "takeoff $it" }
        parts += "heading ${headingText(second.legCourseTrueDeg ?: Double.NaN)}"
        altitudeOf(first)?.let { parts += it }
        airspeedText(second.airspeed)?.let { parts += it }
        return parts.joinToString(", ")
    }

    private fun goAroundSide(diagram: Diagram): String? {
        val side = Js.present(Js.prop(diagram.graphics.goArounds.firstOrNull(), "side")) ?: JsonPrimitive("")
        return when (Js.trim(Js.text(side)).uppercase()) {
            "L", "LEFT" -> "left"
            "R", "RIGHT" -> "right"
            else -> null
        }
    }

    /** The profile [ref] names in the plan's list, by id then by slug (the web's `findProfile`). */
    private fun findProfile(profiles: JsonElement?, ref: JsonElement): JsonObject? {
        val list = (profiles as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
        if (!Js.truthy(ref)) return null
        val byId = list.firstOrNull { p -> Js.present(Js.prop(p, "id")) != null && Js.text(Js.prop(p, "id")) == Js.text(ref) }
        if (byId != null) return byId as? JsonObject
        return list.firstOrNull { p -> Js.truthy(Js.prop(p, "slug")) && Js.strictEquals(Js.prop(p, "slug"), ref) } as? JsonObject
    }

    private fun aircraftText(helicopters: List<JsonElement>): String? {
        if (helicopters.isEmpty()) return null
        val fallback = plan["missionAircraft"]?.takeIf(Js::truthy)
        val counts = LinkedHashMap<Any, Pair<JsonElement, Int>>()
        helicopters.forEach { h ->
            val slug = Js.prop(h, "profileId")?.takeIf(Js::truthy) ?: fallback ?: JsonPrimitive("uh60l")
            val key = mapKey(slug)
            counts[key] = slug to ((counts[key]?.second ?: 0) + 1)
        }
        return counts.values.joinToString(", ") { (slug, n) ->
            val profile = findProfile(plan["aircraftProfiles"], slug)
            val said = listOf(profile?.get("designation"), profile?.get("name")).firstOrNull(Js::truthy)
            "$n × ${if (said != null) Js.text(said) else Js.text(slug).uppercase()}"
        }
    }

    /** A JavaScript Map's key for [value]: text and numbers by value (1 is 1.0, "1" is not 1), an object or a list only itself. */
    private fun mapKey(value: JsonElement): Any = when {
        Js.isString(value) -> "s:" + (value as JsonPrimitive).content
        Js.numberOf(value) != null -> "n:" + Js.text(Js.numberOf(value)!!)
        value is JsonPrimitive -> "b:" + value.content
        else -> Identity(value)
    }

    /** A key equal only to itself: an object or a list in a JavaScript Map. */
    private class Identity(val value: Any) {
        override fun equals(other: Any?): Boolean = other is Identity && other.value === value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

    /** The airspeed covering the most distance across the routes, if any leg has one. */
    private fun enrouteAirspeed(routes: List<RouteEntry>): String? {
        val byText = LinkedHashMap<String, Double>()
        routes.forEach { r ->
            r.item.result.legs.forEach { leg ->
                airspeedText(leg.airspeed)?.let { byText[it] = (byText[it] ?: 0.0) + leg.distNm }
            }
        }
        var best: Pair<String, Double>? = null
        byText.forEach { (text, dist) -> if (best == null || dist > best.second) best = text to dist }
        return best?.first
    }

    private fun routeBindings(route: RouteEntry): Map<String, JsonElement?> {
        val result = route.item.result
        if (result.points.size < 2) problem(route.ref, "${route.name} needs at least two route points to be briefed.")
        val points = result.points
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        val named = points.map { pointName(it.name, "PT") }
        val totals = result.totals
        return mapOf(
            "route.name" to str(route.name),
            "route.summary" to str(
                if (points.size >= 2 && totals != null) {
                    "${route.name}, ${named[0]} to ${named.last()}, ${nmText(totals.distNm)}${totals.timeSec?.let { ", ${durationText(it)}" } ?: ""}"
                } else {
                    null
                },
            ),
            "route.overview" to str(
                if (points.size >= 2) {
                    points.drop(1).mapIndexed { i, p ->
                        "${named[i]} to ${named[i + 1]} ${headingText(p.legCourseTrueDeg ?: Double.NaN)} ${nmText(p.legDistNm ?: Double.NaN)}" +
                            (p.legTimeSec?.let { " ${durationText(it)}" } ?: "")
                    }.joinToString("; ")
                } else {
                    null
                },
            ),
            "route.start" to str(first?.let { clockText(wallOf(it.clockTime)) }),
            "route.end" to str(last?.let { clockText(wallOf(it.clockTime)) }),
            "route.airspeed" to str(changesText(points) { airspeedText(it.airspeed) }),
            "route.altitude" to str(changesText(points) { altitudeOf(it) }),
            "route.distance" to str(totals?.let { nmText(it.distNm) }),
            "route.time" to str(totals?.let { durationText(it.timeSec) }),
            "route.checkpoints" to if (points.isNotEmpty()) {
                JsonArray(
                    points.mapIndexed { i, p ->
                        val type = Js.prop(route.item.named[i].raw, "ptType")
                        JsonPrimitive("${named[i]}${if (Js.truthy(type)) " (${Js.text(type)})" else ""}")
                    },
                )
            } else {
                null
            },
            "route.fuel" to str(totals?.fuelLb?.let(::fuelText)),
        )
    }

    private fun stopBindings(stop: StopEntry, flow: List<Entry>): Map<String, JsonElement?> {
        val diagram = stop.diagram
        val target = stop.target
        val index = flow.indexOfFirst { it === stop }
        val before = flow.subList(0, index).lastOrNull { it is RouteEntry } as RouteEntry?
        val after = flow.subList(index + 1, flow.size).firstOrNull { it is RouteEntry } as RouteEntry?
        val at = before?.let { routePointAt(it, target) }
        val analysis = diagram.analysis
        val rawElevation = if (Js.nonBlank(analysis.gridElevation)) analysis.gridElevation else Js.prop(analysis.results, "elevation")
        val elevation = if (Js.nonBlank(rawElevation) && Js.trim(Js.text(rawElevation)) != "") JsNumber.of(rawElevation) else Double.NaN

        var inbound: String? = null
        if (at != null && at.index > 0) {
            val rp = before.points[at.index - 1]
            val here = at.point
            val parts = arrayListOf("from ${pointName(rp.name, "the RP")}")
            clockText(wallOf(rp.clockTime))?.let { parts[0] += " at $it" }
            airspeedText(here.airspeed)?.let { parts += it }
            parts += nmText(here.legDistNm ?: Double.NaN)
            here.legTimeSec?.let { parts += durationText(it)!! }
            inbound = parts.joinToString(", ")
        }

        val landing = Doghouses.landingHeadingOf(diagram.flightData["landing_hdg"])
        val takeoff = Doghouses.landingHeadingOf(diagram.flightData["takeoff_hdg"])
        val report = (Js.path(context, "weather", "stops") as? JsonObject)?.get(stop.item.id)
        var slope: String? = null
        if (Js.truthy(Js.prop(analysis.terrainData, "stats"))) {
            val call = slopeCall(analysis.terrainData as JsonObject, diagram.flightData["landing_hdg"], report)
            slope = (listOf(call.label) + call.lines).filter { it.isNotEmpty() }.joinToString(". ")
        }

        val grid = target?.let { t ->
            if (Js.nonBlank(JsonPrimitive(t.mgrs))) Js.trim(t.mgrs) else MgrsConverter.toMgrs(t.lat, t.lon)?.format()
        }
        return mapOf(
            "stop.name" to str(stop.name),
            "stop.role" to str(BriefFormats.ROLE_NAMES[stop.role] ?: stop.role),
            "stop.grid" to str(grid),
            "stop.elevation" to str(if (elevation.isFinite()) "${feetText(elevation)} MSL" else null),
            "stop.landingHeading" to str(landing?.let { headingText(it.toDouble()) }),
            "stop.takeoffHeading" to str(takeoff?.let { headingText(it.toDouble()) }),
            "stop.goAround" to str(goAroundSide(diagram)),
            "stop.aircraft" to str(aircraftText(diagram.graphics.helicopters)),
            "stop.arrive" to str(at?.let { clockText(wallOf(it.point.clockTime)) }),
            "stop.inbound" to str(inbound),
            // On along the route that brought it, if that route carries on; else the next route in the flow.
            "stop.departurePlan" to str(
                if (at != null && at.index < before.points.size - 1) departurePlanText(before, at.index)
                else if (after != null) departurePlanText(after) else null,
            ),
            "stop.slope" to str(slope),
            "stop.weather" to str(weatherText(report)),
        )
    }

    /**
     * The slope tile's call for an analysis that carries its slope (`terrainData`, runtime only, so a saved LZ/PZ has none): the web's
     * `slopeCallFor`, through [LzSummary.slopeCall], with its table and wind read from the JSON as the web reads them.
     */
    private fun slopeCall(terrainData: JsonObject, landingHdg: JsonElement?, report: JsonElement?): LzSummary.SlopeCall {
        val maxDeg = Js.numberOf(Js.path(terrainData, "stats", "maxDeg")) ?: Double.NaN
        val byHeading = (terrainData["byHeading"] as? JsonArray)?.mapNotNull { entry ->
            val heading = Js.numberOf(Js.prop(entry, "heading"))?.takeIf { it.isFinite() && it == Math.floor(it) && it in 0.0..359.0 } ?: return@mapNotNull null
            fun slope(field: String) = Js.numberOf(Js.prop(entry, field)) ?: Double.NaN
            LzSummary.HeadingSlope(heading.toInt(), slope("noseHighMaxDeg"), slope("noseLowMaxDeg"), slope("crossSlopeMaxDeg"))
        }
        return LzSummary.slopeCall(maxDeg, byHeading, Doghouses.landingHeadingOf(landingHdg), slopeWindOf(report))
    }

    /** The web's `slopeWindOf`: the steady wind a weather answer gives, or null with no speed. */
    private fun slopeWindOf(report: JsonElement?): LzSummary.SlopeWind? {
        if (report !is JsonObject) return null
        val speed = Js.num(report["wind_spd_kts"])?.takeIf { it >= 0 } ?: return null
        val dir = report["wind_dir"]
        val from = Js.num(dir)?.let { (((Js.round(it) % 360) + 360) % 360).toInt() }
        val variable = Js.stringOf(dir)?.let { Js.trim(it).uppercase() == "VRB" } == true
        return LzSummary.SlopeWind(speed, from, variable)
    }

    private fun threatsText(routes: List<RouteEntry>): String {
        if (threatsLeftOut()) return "Not briefed."
        val list = threatList()
        if (list.isEmpty()) return "None in the plan."
        return "${list.joinToString("; ") { t ->
            val name = threatName(t, "Unnamed threat")
            val lat = Js.num(t["lat"])!!
            val lon = Js.num(t["lon"])!!
            var nearest: Pair<PlanPoint, Double>? = null
            routes.forEach { r ->
                r.points.forEach { p ->
                    val d = Sphere.distanceNm(p.lat, p.lon, lat, lon)
                    if (nearest == null || d < nearest!!.second) nearest = p to d
                }
            }
            val (p, d) = nearest ?: return@joinToString name
            val bearing = Sphere.trueCourseDeg(p.lat, p.lon, lat, lon)
            "$name, ${nmText(d)} ${compassOf(bearing)} of ${pointName(p.name, "the route")}"
        }}."
    }

    /** The brief leaves the threats out (`include.threats` is false). */
    private fun threatsLeftOut(): Boolean = Js.path(doc, "include", "threats").let { it is JsonPrimitive && !it.isString && it.content == "false" }

    private fun threatList(): List<JsonObject> = (plan["threats"] as? JsonArray).orEmpty()
        .filter { it is JsonObject && Js.num(it["lat"]) != null && Js.num(it["lon"]) != null }.map { it as JsonObject }

    private fun threatName(t: JsonObject, fallback: String): String =
        Js.trim(Js.text(Js.present(t["name"]) ?: Js.present(t["label"]) ?: JsonPrimitive(""))).ifEmpty { fallback }

    private fun briefBindings(flow: List<Entry>, routes: List<RouteItem>, missionDate: String?): LinkedHashMap<String, JsonElement?> {
        val flowRoutes = flow.filterIsInstance<RouteEntry>()
        val flowStops = flow.filterIsInstance<StopEntry>()
        val firstRoute = flowRoutes.firstOrNull()
        val lastRoute = flowRoutes.lastOrNull()
        val firstPoint = firstRoute?.points?.firstOrNull()
        val lastPoint = lastRoute?.points?.lastOrNull()
        val takeoffWall = firstPoint?.let { wallOf(it.clockTime) }
        val firstStop = flowStops.firstOrNull()
        val departAt: Pair<Double, Double>? = firstPoint?.let { it.lat to it.lon } ?: firstStop?.target?.let { it.lat to it.lon }
        // Where the flight leaves from is a route's first point: a flow that starts at an LZ/PZ with no route has none.
        val departureName = firstPoint?.let { pointName(it.name, "the start") }

        // The aircraft: the most placed on any LZ/PZ in the flow.
        var aircraft: String? = null
        var mostPlaced = 0
        flowStops.forEach { s ->
            val n = s.diagram.graphics.helicopters.size
            if (n > mostPlaced) {
                mostPlaced = n
                aircraft = aircraftText(s.diagram.graphics.helicopters)
            }
        }

        val light = departAt?.let { (lat, lon) ->
            Light.of(lat, lon, missionDate, offset, takeoffWall?.let { it - offset * 60000 })
        }
        fun event(time: Light.Data.() -> Long?): String? = if (light == null) null else light.time()?.let { instantMinuteText(it, offset) } ?: "none that day"

        fun minutesBefore(id: String): String? {
            val wall = takeoffWall ?: return null
            val minutes = JsNumber.of(standards.values[id]?.value)
            return minuteText((wall - minutes * 60000).toLong())
        }

        val fuel: Double? = if (flowRoutes.isEmpty()) null else flowRoutes.fold<RouteEntry, Double?>(0.0) { sum, r ->
            val lb = r.item.result.totals?.fuelLb
            if (sum == null || lb == null) null else sum + lb
        }
        val distance = flowRoutes.fold(0.0) { sum, r -> sum + (r.item.result.totals?.distNm ?: 0.0) }
        val time: Double? = if (flowRoutes.isEmpty()) null else flowRoutes.fold<RouteEntry, Double?>(0.0) { sum, r ->
            val sec = r.item.result.totals?.timeSec
            if (sum == null || sec == null) null else sum + sec
        }

        val winds = LinkedHashMap<String, Double>()
        flowRoutes.forEach { r ->
            r.item.result.legs.forEach { leg ->
                val spd = leg.wind.speedKts.takeIf { it.isFinite() } ?: return@forEach
                if (spd <= 0) return@forEach
                val text = "${headingText(leg.wind.dirTrue).replace("°", "")}/${Js.text(Js.round(spd))} kt"
                winds[text] = (winds[text] ?: 0.0) + leg.distNm
            }
        }
        var enrouteWind: Pair<String, Double>? = null
        winds.forEach { (text, d) -> if (enrouteWind == null || d > enrouteWind.second) enrouteWind = text to d }

        val weather = context["weather"]
        val departureWx = weatherText(Js.prop(weather, "departure"))
        val enroute = Js.prop(weather, "enroute")
        val enrouteWx = enrouteWind?.let { "Winds ${it.first}" } ?: if (Js.nonBlank(enroute)) Js.trim(Js.text(enroute)) else null
        val arrivalWx = weatherText(Js.prop(weather, "arrival"))
        val wxParts = listOf("Takeoff" to departureWx, "Enroute" to enrouteWx, "Arrival" to arrivalWx).filter { it.second != null }

        val pressure = Js.num(Js.path(weather, "departure", "pressure"))

        // RP inbound: the last leg into each stop a route reaches, when all agree.
        val inboundSpeeds = LinkedHashSet<String?>()
        flowStops.forEach { s ->
            val before = routeBefore(flow, s)
            val at = before?.let { routePointAt(it, s.target) }
            if (at != null && at.index > 0) inboundSpeeds += airspeedText(at.point.airspeed)
        }
        val goArounds = LinkedHashSet(flowStops.mapNotNull { goAroundSide(it.diagram) })

        val rally = (doc["rallyPoints"] as? JsonArray).orEmpty().mapNotNull { r ->
            val route = routes.firstOrNull { Js.strictEquals(JsonPrimitive(it.ref), Js.prop(r, "ref")) }
            val point = route?.named?.firstOrNull { Js.strictEquals(it.raw["id"], Js.prop(r, "pointId")) } ?: return@mapNotNull null
            val label = Js.prop(r, "label")
            val said = if (Js.isText(label, "inbound") || Js.isText(label, "outbound")) " (${Js.text(label)})" else ""
            "${pointName(point.point.name, "a point")}$said"
        }

        val timeline = flow.filter { it !is GoneEntry }.map { f ->
            when (f) {
                is RouteEntry -> {
                    val start = clockText(wallOf(f.points.firstOrNull()?.clockTime))
                    "${if (start != null) "$start " else ""}${f.name}"
                }
                else -> {
                    val stop = f as StopEntry
                    val at = routeBefore(flow, stop)?.let { routePointAt(it, stop.target) }
                    val arrive = at?.let { clockText(wallOf(it.point.clockTime)) }
                    "${if (arrive != null) "$arrive " else ""}${namedWithRole(stop)}"
                }
            }
        }

        // The whole mission in a sentence.
        val groups = ArrayList<Pair<String, MutableList<String>>>()
        flowStops.forEach { s ->
            val last = groups.lastOrNull()
            if (last != null && last.first == s.role) last.second += s.name else groups += s.role to mutableListOf(s.name)
        }
        fun joinNames(names: List<String>) = if (names.size <= 1) names.joinToString("") else "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        val summaryParts = arrayListOf(aircraft ?: "the flight")
        if (departureName != null) summaryParts += "from $departureName"
        val groupWords = groups.map { (role, names) -> "${ROLE_WORDS[role] ?: "at"} ${joinNames(names)}" }
        var summary = (listOf(listOfNotNull(summaryParts.joinToString(" "), groupWords.firstOrNull()).joinToString(" ")) + groupWords.drop(1)).joinToString(", ")
        val endWall = lastPoint?.let { wallOf(it.clockTime) }
        if (lastPoint != null) summary += ", back at ${pointName(lastPoint.name, "the end")}${endWall?.let { " at ${clockText(it)}" } ?: ""}"
        summary += "."

        val notams = Js.present(context["notams"]) ?: Js.path(weather, "departure", "notams")
        return linkedMapOf(
            "mission.name" to str(Js.trim(Js.text(plan["name"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))).ifEmpty { "the mission" }),
            "mission.date" to str(dateText(missionDate)),
            "mission.distance" to str(if (flowRoutes.isNotEmpty()) nmText(distance) else null),
            "mission.flightTime" to str(time?.let(::durationText)),
            "flight.aircraft" to str(aircraft),
            "flight.summary" to str(summary),
            "weather.summary" to str(if (wxParts.isNotEmpty()) "${wxParts.joinToString(". ") { (k, v) -> "$k: $v" }}." else null),
            "weather.departure" to str(departureWx),
            "weather.enroute" to str(enrouteWx),
            "weather.arrival" to str(arrivalWx),
            "weather.altimeter" to str(pressure?.let { "${JsNumber.toFixed(it, 2)} inHg" }),
            "light.bmnt" to str(event { bmnt }),
            "light.sunrise" to str(event { sunrise }),
            "light.sunset" to str(event { sunset }),
            "light.eent" to str(event { eent }),
            "light.moonrise" to str(event { moonrise }),
            "light.moonset" to str(event { moonset }),
            "light.illumination" to str(light?.let { percentText(it.illumination) }),
            "light.summary" to str(
                light?.let {
                    "BMNT ${event { bmnt }}, sunrise ${event { sunrise }}, sunset ${event { sunset }}, EENT ${event { eent }}, " +
                        "moonrise ${event { moonrise }}, moonset ${event { moonset }}, illumination ${percentText(it.illumination)}."
                },
            ),
            "notams.summary" to str(notamsText(notams)),
            "threats.summary" to str(threatsText(flowRoutes)),
            "timeline" to if (timeline.isNotEmpty()) JsonArray(timeline.map(::JsonPrimitive)) else null,
            "departure.point" to str(departureName),
            "departure.time" to str(clockText(takeoffWall)),
            "departure.commoCheck" to str(minutesBefore("commoCheckMinutes")),
            "departure.taxi" to str(minutesBefore("taxiLoadMinutes")),
            "departure.lineup" to str(minutesBefore("lineupMinutes")),
            "departure.fuel" to str(fuel?.let(::fuelText)),
            "departure.plan" to str(departurePlanText(firstRoute)),
            "arrival.point" to str(lastPoint?.let { pointName(it.name, "the end") } ?: flowStops.lastOrNull()?.name),
            "arrival.time" to str(clockText(endWall)),
            "routes.enrouteAirspeed" to str(enrouteAirspeed(flowRoutes)),
            "routes.rpInboundAirspeed" to str(if (inboundSpeeds.size == 1 && null !in inboundSpeeds) inboundSpeeds.first() else null),
            "stops.goAround" to str(if (goArounds.size == 1) goArounds.first() else null),
            "rally.points" to str(if (rally.isNotEmpty()) rally.joinToString(", ") else null),
        )
    }

    /** A stop's name with its role in front, unless the name already starts with it ("LZ HAWK", not "LZ LZ HAWK"). */
    private fun namedWithRole(stop: StopEntry): String {
        val role = BriefFormats.ROLE_NAMES[stop.role] ?: stop.role
        return if (stop.name.uppercase().startsWith("${role.uppercase()} ")) stop.name else "$role ${stop.name}"
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // The template: steps, items, scripts.

    private val sections: List<SectionAt> = template.paragraphs.flatMap { p -> p.sections.map { SectionAt(it, p) } }

    /** Side-by-side sections that share a step and a repeat are one step. */
    private fun stepGroups(): List<Group> {
        val groups = ArrayList<Group>()
        sections.forEach { s ->
            val last = groups.lastOrNull()
            if (last != null && last.step == s.section.step && last.repeat == s.section.repeat) last.sections += s
            else groups += Group(s.section.step, s.section.repeat, mutableListOf(s))
        }
        return groups
    }

    private fun repeatsFor(group: Group, entry: Entry): Boolean =
        if (entry is RouteEntry) group.repeat == "route" else group.repeat == (entry as StopEntry).role

    /**
     * The steps, in the order they are briefed: each group of the template in turn, except that a run of repeated groups is briefed in
     * flow order (departure, the first route, the LZs it reaches, the next route...), as the flight meets them.
     */
    private fun planSteps(flow: List<Entry>): List<Planned> {
        val groups = stepGroups()
        val steps = ArrayList<Planned>()
        var i = 0
        while (i < groups.size) {
            if (groups[i].repeat == null) {
                steps += Planned(groups[i], null)
                i += 1
                continue
            }
            val run = ArrayList<Group>()
            while (i < groups.size && groups[i].repeat != null) run += groups[i++]
            flow.filter { it !is GoneEntry }.forEach { entry ->
                run.filter { repeatsFor(it, entry) }.forEach { steps += Planned(it, entry) }
            }
        }
        return steps
    }

    private fun stepIdOf(planned: Planned): String =
        if (planned.entry != null) "${planned.group.step}@${planned.entry.ref}" else planned.group.step.toString()

    private fun answerKey(section: BriefSection, item: BriefItem, entry: Entry?): String =
        "${section.id}.${item.id}${if (entry != null) "@${entry.ref}" else ""}"

    /** Joins parts, merging neighbouring text, so a sentence is as few parts as it can be. */
    private fun tidy(parts: List<Part>): List<Part> {
        val out = ArrayList<Part>()
        parts.forEach { p ->
            val last = out.lastOrNull()
            if (p is Part.Text && last is Part.Text) {
                last.text += p.text
            } else if (!(p is Part.Text && p.text.isEmpty())) {
                out += if (p is Part.Text) Part.Text(p.text) else p
            }
        }
        return out
    }

    /** A sentence with placeholders as parts, each placeholder through [resolve] (parts, or null for a write-in). */
    private fun fill(sentence: String, resolve: (String) -> List<Part>?, labelOf: (String) -> String? = { null }): List<Part> {
        val parts = ArrayList<Part>()
        var last = 0
        PLACEHOLDER.findAll(sentence).forEach { match ->
            parts += Part.Text(sentence.substring(last, match.range.first))
            val name = match.groupValues[1]
            val value = resolve(name)
            if (value == null) parts += Part.WriteIn(name, labelOf(name)) else parts += value
            last = match.range.last + 1
        }
        parts += Part.Text(sentence.substring(last))
        return tidy(parts)
    }

    private fun valueParts(value: JsonElement?): List<Part>? {
        if (value == null || value is JsonNull) return null
        if (value is JsonArray) return if (value.isNotEmpty()) listOf(Part.Text(Js.join(value, "; "))) else null
        val text = Js.text(value)
        return if (Js.trim(text).isNotEmpty()) listOf(Part.Text(text)) else null
    }

    private fun valueParts(text: String?): List<Part>? = valueParts(text?.let(::JsonPrimitive))

    /** `name.split(/:(.*)/s)`: the kind before the first colon and what follows it, or the name alone. */
    private fun splitKind(name: String): Pair<String, String?> {
        val colon = name.indexOf(':')
        return if (colon < 0) name to null else name.substring(0, colon) to name.substring(colon + 1)
    }

    private fun bindingLabel(key: String?): String? = key?.let { bindings[it]?.label }

    /** What a procedure item says as chosen: the standard with the person's fields, their own words, or a write-in. */
    private fun procedureParts(item: BriefItem, answer: JsonElement?, resolveBinding: (String) -> JsonElement?): List<Part>? {
        val mode = Js.prop(answer, "mode")
        if (Js.isText(mode, "custom")) {
            val value = Js.prop(answer, "value")
            return if (Js.nonBlank(value)) listOf(Part.Text(Js.trim(Js.text(value)))) else null
        }
        if (!Js.isText(mode, "standard") || item.standard.isNullOrEmpty()) return null
        val procedure = standards.procedures[item.standard] ?: return null
        val fields = Js.prop(answer, "fields") as? JsonObject ?: CompileBrief.EMPTY
        val sentences = procedure.script.map { sentence ->
            fill(sentence, { name ->
                val (kind, key) = splitKind(name)
                when (kind) {
                    "value" -> valueParts(key?.let { standards.values[it]?.value })
                    "field" -> valueParts(key?.let { fields[it] }?.takeIf(Js::nonBlank)?.let { Js.trim(Js.text(it)) })
                    "bind" -> valueParts(key?.let(resolveBinding))
                    else -> null
                }
            }, { name ->
                val (kind, key) = splitKind(name)
                when (kind) {
                    "field" -> procedure.fields.orEmpty().firstOrNull { it.id == key }?.label
                    "bind" -> bindingLabel(key)
                    else -> null
                }
            })
        }
        return tidy(sentences.flatMapIndexed { i, s -> if (i == 0) s else listOf(Part.Text(" ")) + s })
    }

    private fun partsText(parts: List<Part>): String = parts.joinToString("") { if (it is Part.WriteIn) CompileBrief.WRITE_IN else (it as Part.Text).text }

    private fun partsJson(parts: List<Part>): JsonArray = buildJsonArray {
        parts.forEach { part ->
            add(
                when (part) {
                    is Part.Text -> buildJsonObject { put("text", part.text) }
                    is Part.WriteIn -> buildJsonObject {
                        put("writeIn", part.name)
                        put("label", part.label?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------

    fun compile(): JsonObject {
        val stops = (plan["lzs"] as? JsonArray).orEmpty().filter { it is JsonObject && it["id"] != null }.map { readStop(it as JsonObject) }
        val routeSets = (plan["routeSets"] as? JsonArray).orEmpty()
        val datedRoute = routeSets.flatMap { s -> (Js.path(s, "data", "routes") as? JsonArray).orEmpty() }.firstOrNull { r ->
            val date = Js.path(r, "plan", "date")
            ISO_DATE.matches(if (Js.truthy(date)) Js.text(date) else "")
        }
        val missionDate = datedRoute?.let { Js.text(Js.path(it, "plan", "date")) } ?: localDateOf(Js.present(context["generatedAt"]), offset)
        val routes = routeSets.filter { it is JsonObject && it["id"] != null }.flatMap { readRoutes(it as JsonObject, missionDate) }
        val flow = resolveFlow(stops, routes)

        val brief = briefBindings(flow, routes, missionDate)
        val perRoute = LinkedHashMap<String, Map<String, JsonElement?>>()
        flow.filterIsInstance<RouteEntry>().forEach { perRoute[it.ref] = routeBindings(it) }
        val perStop = LinkedHashMap<String, Map<String, JsonElement?>>()
        flow.filterIsInstance<StopEntry>().forEach { perStop[it.ref] = stopBindings(it, flow) }
        flow.filterIsInstance<StopEntry>().filter { !Js.truthy(perStop.getValue(it.ref)["stop.arrive"]) }.forEach { s ->
            problem(s.ref, "No route in the flow reaches ${s.name} before it, so it has no arrival time.")
        }

        fun bindingFor(entry: Entry?): (String) -> JsonElement? = { name ->
            val binding = bindings[name]
            when {
                binding == null -> null
                binding.scope == "brief" -> brief[name]
                entry == null -> null
                binding.scope == "route" -> if (entry is RouteEntry) perRoute[entry.ref]?.get(name) else null
                else -> if (entry is StopEntry) perStop[entry.ref]?.get(name) else null
            }
        }

        // Constants: the person's list, else the ones offered; each typed, from the plan, standard, or a write-in.
        val constantEntries: List<JsonObject> = (doc["constants"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: catalog.filter { it.offered }.map { JsonObject(mapOf("id" to JsonPrimitive(it.id))) }
        class Constant(val id: String, val label: String, val short: String, val value: JsonElement?, val source: String)
        val constants = constantEntries.map { entry ->
            val known = catalog.firstOrNull { Js.isText(entry["id"], it.id) }
            val label = known?.label ?: Js.trim(Js.text(Js.present(entry["label"]) ?: JsonPrimitive(""))).ifEmpty { "Constant" }
            val short = known?.short ?: Js.slice(label.uppercase(), 0, 12)
            var value: JsonElement? = null
            var source = "blank"
            if (Js.nonBlank(entry["value"])) {
                value = JsonPrimitive(Js.trim(Js.text(entry["value"])))
                source = "typed"
            } else if (known?.bind != null && Js.truthy(brief[known.bind])) {
                value = brief[known.bind]
                source = "plan"
            } else if (known?.standard != null) {
                val v = standards.values.getValue(known.standard)
                value = JsonPrimitive("${Js.text(v.value)}${if (!v.unit.isNullOrEmpty()) " ${v.unit}" else ""}")
                source = "standard"
            }
            Constant(Js.text(Js.present(entry["id"]) ?: JsonPrimitive(label)), label, short, value, source)
        }
        fun constantValue(id: String?): JsonElement? = constants.firstOrNull { it.id == id }?.value

        // Steps, then their pages, so the packet's own page count can be said on its first page.
        val options = optionsOf()
        val planned = planSteps(flow)
        val flowStops = flow.filterIsInstance<StopEntry>()
        val flowRoutes = flow.filterIsInstance<RouteEntry>()
        val iimcItem = sections.flatMap { s -> s.section.items.map { s to it } }.firstOrNull { it.second.standard == "iimc" }
        val iimcAnswer = iimcItem?.let { answers[answerKey(it.first.section, it.second, null)] }
        val hasIimcCard = Js.truthy(options["iimc"]) &&
            (Js.isText(Js.prop(iimcAnswer, "mode"), "standard") || Js.isText(Js.prop(iimcAnswer, "mode"), "custom"))

        val pages = ArrayList<JsonObject>()
        planned.forEachIndexed { i, st ->
            pages += buildJsonObject {
                put("n", i + 1)
                put("kind", if (i == 0) "cover" else "step")
                put("stepId", stepIdOf(st))
            }
        }
        if (Js.truthy(options["lzCards"])) {
            flowStops.forEach { s ->
                pages += buildJsonObject {
                    put("n", pages.size + 1)
                    put("kind", "lzCard")
                    put("ref", s.ref)
                    put("title", "${BriefFormats.ROLE_NAMES[s.role]} card · ${s.name}")
                }
            }
        }
        if (Js.truthy(options["navLogs"])) {
            flowRoutes.forEach { r ->
                pages += buildJsonObject {
                    put("n", pages.size + 1)
                    put("kind", "navLog")
                    put("ref", r.ref)
                    put("title", "Nav log · ${r.name}")
                }
            }
        }
        // Strip charts, three to a page, a route's strips together and in the order they are flown.
        val stripScale = Js.numberOf(options["stripScale"])?.takeIf { it in StripCharts.SCALES } ?: StripCharts.SCALES[0]
        val threatsShown = if (threatsLeftOut()) emptyList() else threatList()
        val strips = if (Js.truthy(options["strips"])) {
            StripCharts.of(flowRoutes, flowStops.filter { it.target != null }, threatsShown, stripScale, ::problem, ::altitudeOf)
        } else {
            emptyList()
        }
        flowRoutes.forEach { r ->
            val own = strips.filter { Js.isText(it["ref"], r.ref) }
            for (i in own.indices step 3) {
                val shown = own.subList(i, minOf(i + 3, own.size))
                val parts = if (shown.size > 1) "${Js.text(shown.first()["part"])}–${Js.text(shown.last()["part"])}" else Js.text(shown.first()["part"])
                val range = if (own.size > 3) " ($parts of ${own.size})" else ""
                pages += buildJsonObject {
                    put("n", pages.size + 1)
                    put("kind", "strips")
                    put("ref", r.ref)
                    put("title", "Strip charts · ${r.name}$range")
                    put("strips", JsonArray(shown.map { it.getValue("id") }))
                }
            }
        }
        if (hasIimcCard) {
            pages += buildJsonObject {
                put("n", pages.size + 1)
                put("kind", "iimc")
                put("title", "IIMC card")
                put("itemId", "${iimcItem!!.first.section.id}.${iimcItem.second.id}")
            }
        }

        // What the packet holds, by section, never by page: where Chromium breaks the printed pages is not known here, so a page number
        // the brief said could be wrong (the PDF's own footer numbers every page truly).
        fun count(kind: String) = pages.count { Js.isText(it["kind"], kind) }
        val inventory = listOfNotNull(
            "the brief (${plural(planned.size, "step", "steps")})",
            if (count("lzCard") > 0) plural(count("lzCard"), "LZ/PZ card", "LZ/PZ cards") else null,
            if (count("navLog") > 0) plural(count("navLog"), "nav log", "nav logs") else null,
            StripCharts.inventory(strips.size),
            if (count("iimc") > 0) "the IIMC card" else null,
        )
        val inventoryText = if (inventory.size > 1) "${inventory.dropLast(1).joinToString(", ")} and ${inventory.last()}" else inventory[0]
        brief["packet.pages"] = JsonPrimitive(pages.size.toString())
        brief["packet.inventory"] = JsonPrimitive(inventoryText)

        val scripts = doc["scripts"] as? JsonObject ?: CompileBrief.EMPTY

        val steps = planned.mapIndexed { index, st ->
            val group = st.group
            val entry = st.entry
            val id = stepIdOf(st)
            val first = group.sections[0]
            val resolveBinding = bindingFor(entry)
            val instanceName = when (entry) {
                is RouteEntry -> entry.name
                is StopEntry -> entry.name
                else -> null
            }
            val baseTitle = first.section.stepTitle ?: if (group.sections.size > 1) first.paragraph.title else first.section.title
            val lastSection = group.sections.last()

            val items = ArrayList<JsonObject>()
            val sentences = ArrayList<List<Part>>()
            group.sections.forEach { at ->
                val section = at.section
                val itemValues = HashMap<String, List<Part>?>()
                section.items.forEach { item ->
                    val key = answerKey(section, item, entry)
                    val answer = answers[key]
                    var parts: List<Part>? = null
                    var source = "blank"
                    val typed = Js.prop(answer, "value")
                    if (item.kind == "info") {
                        source = "info"
                    } else if (item.kind == "procedure") {
                        parts = procedureParts(item, answer, resolveBinding)
                        source = if (parts != null) Js.text(Js.prop(answer, "mode")) else "blank"
                    } else if (Js.nonBlank(typed) || (typed is JsonArray && typed.isNotEmpty())) {
                        parts = valueParts(typed)
                        source = "typed"
                    } else if (item.bind != null) {
                        parts = valueParts(resolveBinding(item.bind))
                        source = if (parts != null) "plan" else "blank"
                    }
                    itemValues[item.id] = parts
                    items += buildJsonObject {
                        put("id", "${section.id}.${item.id}")
                        put("key", key)
                        put("label", item.label)
                        put("kind", item.kind)
                        put("source", source)
                        put("text", parts?.let { JsonPrimitive(partsText(it)) } ?: JsonNull)
                        put("writeIn", parts == null && item.kind != "info")
                    }
                }
                section.script.forEach { sentence ->
                    sentences += fill(sentence, { name ->
                        if (itemValues.containsKey(name)) return@fill itemValues[name]
                        val (kind, key) = splitKind(name)
                        when (kind) {
                            "bind" -> valueParts(key?.let(resolveBinding))
                            "const" -> valueParts(constantValue(key))
                            "item" -> itemPlaceholder(key ?: "", resolveBinding)
                            else -> null
                        }
                    }, { name ->
                        section.items.firstOrNull { it.id == name }?.label ?: run {
                            val (kind, key) = splitKind(name)
                            when (kind) {
                                "bind" -> bindingLabel(key)
                                "const" -> constants.firstOrNull { it.id == key }?.label
                                "item" -> otherItem(key ?: "")?.second?.label
                                else -> null
                            }
                        }
                    })
                }
            }

            val edited = Js.stringOf(scripts[id])?.let { Js.trim(it).isNotEmpty() } == true
            val facts = LinkedHashSet(group.sections.flatMap { it.section.facts.orEmpty() }).map { name ->
                buildJsonObject {
                    put("key", name)
                    bindings[name]?.label?.let { put("label", it) }
                    val v = resolveBinding(name)
                    put("value", if (v is JsonArray) JsonPrimitive(Js.join(v, "; ")) else v ?: JsonNull)
                }
            }

            buildJsonObject {
                put("id", id)
                put("number", if (group.sections.size > 1) "${first.section.id}–${lastSection.section.id}" else first.section.id)
                put("title", if (instanceName != null) "$baseTitle · $instanceName" else baseTitle)
                put("who", first.section.who ?: first.paragraph.who ?: template.defaultWho)
                put("sections", JsonArray(group.sections.map { JsonPrimitive(it.section.id) }))
                put("ref", entry?.ref?.let(::JsonPrimitive) ?: JsonNull)
                put("role", (entry as? StopEntry)?.role?.let(::JsonPrimitive) ?: JsonNull)
                put("page", index + 1)
                put("figure", figureOf(first.section.figure, entry, flow))
                put("facts", JsonArray(facts))
                put("items", JsonArray(items))
                put(
                    "script",
                    if (edited) {
                        JsonArray(listOf(partsJson(listOf(Part.Text(Js.trim(Js.stringOf(scripts[id])!!))))))
                    } else {
                        JsonArray(sentences.filter { it.isNotEmpty() }.map(::partsJson))
                    },
                )
                put("scriptEdited", edited)
            }
        }

        return buildJsonObject {
            put("format", CompileBrief.MODEL_FORMAT)
            put("version", CompileBrief.MODEL_VERSION)
            put(
                "template",
                buildJsonObject {
                    put("id", template.id)
                    put("version", template.version)
                    put("title", template.title)
                    put("shortTitle", template.shortTitle)
                },
            )
            put("generatedAt", Js.present(context["generatedAt"])?.let(::asJavaScriptWrites) ?: JsonNull)
            put("utcOffsetMinutes", Js.number(offset))
            put(
                "mission",
                buildJsonObject {
                    putValue("name", brief["mission.name"])
                    putValue("date", brief["mission.date"])
                    putValue("summary", brief["flight.summary"])
                },
            )
            put("options", options)
            put(
                "constants",
                JsonArray(
                    constants.map { c ->
                        buildJsonObject {
                            put("id", c.id)
                            put("label", c.label)
                            put("short", c.short)
                            putValue("value", c.value)
                            put("source", c.source)
                        }
                    },
                ),
            )
            put("places", placesOf(flow))
            put("geometry", geometryOf(flow))
            put(
                "flow",
                JsonArray(
                    flow.map { f ->
                        buildJsonObject {
                            put("ref", f.ref)
                            when (f) {
                                is GoneEntry -> put("gone", true)
                                is RouteEntry -> {
                                    put("kind", "route")
                                    put("name", f.name)
                                    putValue("start", perRoute.getValue(f.ref)["route.start"])
                                    putValue("end", perRoute.getValue(f.ref)["route.end"])
                                }
                                is StopEntry -> {
                                    put("kind", "stop")
                                    put("role", f.role)
                                    put("name", f.name)
                                    putValue("arrive", perStop.getValue(f.ref)["stop.arrive"])
                                    putValue("grid", perStop.getValue(f.ref)["stop.grid"])
                                }
                            }
                        }
                    },
                ),
            )
            put("steps", JsonArray(steps))
            put("pages", JsonArray(pages))
            put("strips", JsonArray(strips))
            put("problems", JsonArray(problems))
        }
    }

    /** The section and item an `{item:5.a.v}` placeholder names, as the web finds them (`lastIndexOf` and `slice`). */
    private fun otherItem(key: String): Pair<SectionAt, BriefItem>? {
        val dot = key.lastIndexOf('.')
        val other = sections.firstOrNull { it.section.id == Js.slice(key, 0, dot) } ?: return null
        val item = other.section.items.firstOrNull { it.id == Js.slice(key, dot + 1) } ?: return null
        return other to item
    }

    /** An `{item:…}` placeholder: another section's item, as answered, chosen or found in the plan. */
    private fun itemPlaceholder(key: String, resolveBinding: (String) -> JsonElement?): List<Part>? {
        val (other, otherItem) = otherItem(key) ?: return null
        val answer = answers[answerKey(other.section, otherItem, null)]
        if (otherItem.kind == "procedure") return procedureParts(otherItem, answer, resolveBinding)
        val value = Js.prop(answer, "value")
        if (Js.nonBlank(value)) return valueParts(value)
        return otherItem.bind?.let { valueParts(resolveBinding(it)) }
    }

    /** The document's options over the defaults, as `{...defaults, ...doc.options}` makes them. */
    private fun optionsOf(): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        listOf("script", "constants", "lzCards", "navLogs", "strips", "iimc", "writeIns").forEach { merged[it] = JsonPrimitive(true) }
        (doc["options"] as? JsonObject)?.let { o -> Js.orderedKeys(o.keys).forEach { merged[it] = asJavaScriptWrites(o.getValue(it)) } }
        return JsonObject(Js.orderedKeys(merged.keys).associateWith { merged.getValue(it) })
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Figures and places.

    /**
     * What the figures draw, once for the whole brief (each figure names what it shows by reference), in lat/lon with the words already
     * written, so the server that draws them decides nothing (docs/MISSION_BRIEF.md §2.6).
     */
    private fun geometryOf(flow: List<Entry>): JsonObject {
        val routes = LinkedHashMap<String, JsonObject>()
        flow.filterIsInstance<RouteEntry>().forEach { r ->
            val points = r.points
            routes[r.ref] = buildJsonObject {
                put("name", r.name)
                put("color", Js.stringOf(r.item.raw["color"])?.let(::JsonPrimitive) ?: JsonNull)
                put("line", JsonArray(r.item.points.map { pair(it.lat, it.lon) }))
                put(
                    "points",
                    JsonArray(
                        points.mapIndexed { i, p ->
                            buildJsonObject {
                                put("name", pointName(p.name, "PT"))
                                put("type", Js.present(r.item.named[i].raw["ptType"])?.let(::asJavaScriptWrites) ?: JsonNull)
                                put("lat", Js.number(p.lat))
                                put("lon", Js.number(p.lon))
                            }
                        },
                    ),
                )
                put(
                    "log",
                    JsonArray(
                        points.drop(1).mapIndexed { i, p ->
                            buildJsonObject {
                                put("from", pointName(points[i].name, "PT"))
                                put("to", pointName(p.name, "PT"))
                                put("course", headingText(p.legCourseTrueDeg ?: Double.NaN))
                                put("distance", nmText(p.legDistNm ?: Double.NaN))
                                putValue("time", str(p.legTimeSec?.let(::durationText)))
                                putValue("clock", str(clockText(wallOf(p.clockTime))))
                                putValue("altitude", str(altitudeOf(p)))
                                putValue("airspeed", str(airspeedText(p.airspeed)))
                            }
                        },
                    ),
                )
            }
        }
        val stops = LinkedHashMap<String, JsonObject>()
        flow.filterIsInstance<StopEntry>().forEach { stop ->
            val target = stop.target ?: return@forEach
            val diagram = stop.diagram
            val boundary = diagram.analysis.customLZ.takeIf(Js::truthy) ?: diagram.analysis.detectedLZ
            stops[stop.ref] = buildJsonObject {
                put("name", stop.name)
                put("role", stop.role)
                put("lat", Js.number(target.lat))
                put("lon", Js.number(target.lon))
                put(
                    "boundary",
                    (boundary as? JsonArray)?.let { list ->
                        JsonArray(list.mapNotNull { p -> (p as? JsonArray)?.let { pairOf(it.getOrNull(0), it.getOrNull(1)) } })
                    } ?: JsonNull,
                )
                put(
                    "aircraft",
                    JsonArray(
                        diagram.graphics.helicopters.mapNotNull { h ->
                            val lat = Js.num(Js.prop(h, "lat")) ?: return@mapNotNull null
                            val lon = Js.num(Js.prop(h, "lon")) ?: return@mapNotNull null
                            buildJsonObject {
                                put("lat", Js.number(lat))
                                put("lon", Js.number(lon))
                                put("heading", Js.number(JsNumber.of(Js.prop(h, "heading")).takeIf { it.isFinite() } ?: 0.0))
                            }
                        },
                    ),
                )
                put("landingHeading", Doghouses.landingHeadingOf(diagram.flightData["landing_hdg"])?.let(::JsonPrimitive) ?: JsonNull)
                put("takeoffHeading", Doghouses.landingHeadingOf(diagram.flightData["takeoff_hdg"])?.let(::JsonPrimitive) ?: JsonNull)
                putValue("goAround", str(goAroundSide(diagram)))
            }
        }
        val threats = if (threatsLeftOut()) {
            emptyList()
        } else {
            threatList().map { t ->
                buildJsonObject {
                    put("name", threatName(t, "Threat"))
                    put("lat", Js.number(Js.num(t["lat"])!!))
                    put("lon", Js.number(Js.num(t["lon"])!!))
                }
            }
        }
        val rally = (doc["rallyPoints"] as? JsonArray).orEmpty().mapNotNull { r ->
            val route = flow.firstOrNull { it is RouteEntry && Js.strictEquals(JsonPrimitive(it.ref), Js.prop(r, "ref")) } as RouteEntry?
            val point = route?.item?.named?.firstOrNull { Js.strictEquals(it.raw["id"], Js.prop(r, "pointId")) } ?: return@mapNotNull null
            buildJsonObject {
                put("name", pointName(point.point.name, "RALLY"))
                put("label", if (Js.isText(Js.prop(r, "label"), "outbound")) "outbound" else "inbound")
                put("lat", Js.number(point.point.lat))
                put("lon", Js.number(point.point.lon))
            }
        }
        return buildJsonObject {
            put("routes", JsonObject(routes))
            put("stops", JsonObject(stops))
            put("threats", JsonArray(threats))
            put("rally", JsonArray(rally))
        }
    }

    /**
     * Where and when the weather is wanted: the departure (the first route's first point, at its clock), the arrival (the last route's last
     * point) and each stop (its target, at the time a route reaches it), each time as an instant at the offset; null where the plan does not
     * say. The caller asks /api/weather with these and hands the answers back as `context.weather`.
     */
    private fun placesOf(flow: List<Entry>): JsonObject {
        fun instant(wall: Long?): JsonElement = wall?.let { BriefText.isoString(it - offset * 60000) }?.let(::JsonPrimitive) ?: JsonNull
        val routes = flow.filterIsInstance<RouteEntry>().filter { it.points.isNotEmpty() }
        val first = routes.firstOrNull()?.points?.first()
        val last = routes.lastOrNull()?.points?.last()
        fun place(p: PlanPoint?): JsonElement = if (p == null) {
            JsonNull
        } else {
            buildJsonObject {
                put("lat", Js.number(p.lat))
                put("lon", Js.number(p.lon))
                put("at", instant(wallOf(p.clockTime)))
            }
        }
        return buildJsonObject {
            put("departure", place(first))
            put("arrival", place(last))
            put(
                "stops",
                JsonArray(
                    flow.filterIsInstance<StopEntry>().mapNotNull { stop ->
                        val target = stop.target ?: return@mapNotNull null
                        val at = routeBefore(flow, stop)?.let { routePointAt(it, target) }
                        buildJsonObject {
                            put("id", stop.item.id)
                            put("ref", stop.ref)
                            put("lat", Js.number(target.lat))
                            put("lon", Js.number(target.lon))
                            put("at", at?.let { instant(wallOf(it.point.clockTime)) } ?: JsonNull)
                        }
                    },
                ),
            )
        }
    }

    /**
     * What a step's figure shows, as data the renderer draws: the area, the item in focus, and what is on it. The item in focus is the step's
     * own (a route, an LZ/PZ), and at the departure the route the flight leaves on, which is lit.
     */
    private fun figureOf(kind: String?, entry: Entry?, all: List<Entry>): JsonElement {
        if (kind == "none") return JsonNull
        val routesIn = all.filterIsInstance<RouteEntry>()
        val stopsIn = all.filterIsInstance<StopEntry>().filter { it.target != null }
        fun pointsOf(r: RouteEntry) = r.points.map { it.lat to it.lon }
        val focus: List<Pair<Double, Double>> = when {
            kind == "route" && entry != null -> if (entry is RouteEntry) pointsOf(entry) else emptyList()
            kind == "stop" && (entry as? StopEntry)?.target != null -> stopArea(entry)
            kind == "departure" && routesIn.isNotEmpty() -> pointsOf(routesIn[0]).take(3)
            else -> routesIn.flatMap(::pointsOf) + stopsIn.map { it.target!!.lat to it.target!!.lon }
        }
        return buildJsonObject {
            kind?.let { put("kind", it) }
            put(
                "ref",
                when {
                    entry != null -> JsonPrimitive(entry.ref)
                    kind == "departure" && routesIn.isNotEmpty() -> JsonPrimitive(routesIn[0].ref)
                    else -> JsonNull
                },
            )
            put("bounds", boundsOf(focus))
            put("routes", JsonArray(routesIn.map { JsonPrimitive(it.ref) }))
            put("stops", JsonArray(stopsIn.map { JsonPrimitive(it.ref) }))
            put(
                "rallyPoints",
                if (kind == "rally" && doc["rallyPoints"] is JsonArray) {
                    JsonArray(
                        (doc["rallyPoints"] as JsonArray).filterIsInstance<JsonObject>().map { r ->
                            buildJsonObject {
                                r["ref"]?.let { put("ref", asJavaScriptWrites(it)) }
                                r["pointId"]?.let { put("pointId", asJavaScriptWrites(it)) }
                            }
                        },
                    )
                } else {
                    JsonArray(emptyList())
                },
            )
        }
    }

    /** An LZ/PZ's area for its figure: its boundary if it has one, else its target, padded to about 300 m either way. */
    private fun stopArea(stop: StopEntry): List<Pair<Double, Double>> {
        val analysis = stop.diagram.analysis
        val boundary = analysis.customLZ.takeIf(Js::truthy) ?: analysis.detectedLZ
        val corners = (boundary as? JsonArray).orEmpty().mapNotNull { p ->
            val pair = p as? JsonArray ?: return@mapNotNull null
            val lat = Js.num(pair.getOrNull(0)) ?: return@mapNotNull null
            val lon = Js.num(pair.getOrNull(1)) ?: return@mapNotNull null
            lat to lon
        }
        val target = stop.target!!
        val dLat = 300 / 111320.0
        // StrictMath: the same bits on the JVM and every device. compile.json's bounds are compared exactly, and agree.
        val dLon = dLat / maxOf(0.01, StrictMath.cos((target.lat * Math.PI) / 180))
        return corners + listOf(target.lat - dLat to target.lon - dLon, target.lat + dLat to target.lon + dLon)
    }

    /** `[[south, west], [north, east]]` round the points, a tenth wider each way (at least 0.002°); null for none. */
    private fun boundsOf(points: List<Pair<Double, Double>>): JsonElement {
        if (points.isEmpty()) return JsonNull
        var s = Double.POSITIVE_INFINITY
        var w = Double.POSITIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        var e = Double.NEGATIVE_INFINITY
        points.forEach { (lat, lon) ->
            s = minOf(s, lat)
            n = maxOf(n, lat)
            w = minOf(w, lon)
            e = maxOf(e, lon)
        }
        val padLat = maxOf(0.002, (n - s) * 0.1)
        val padLon = maxOf(0.002, (e - w) * 0.1)
        return JsonArray(listOf(pair(s - padLat, w - padLon), pair(n + padLat, e + padLon)))
    }

    private fun pair(lat: Double, lon: Double): JsonArray = JsonArray(listOf(Js.number(lat), Js.number(lon)))

    private fun pairOf(lat: JsonElement?, lon: JsonElement?): JsonArray? {
        val a = Js.num(lat) ?: return null
        val b = Js.num(lon) ?: return null
        return pair(a, b)
    }

    private companion object {
        /** A stop is at a route point within this distance of its target. */
        const val STOP_MATCH_M = 500.0
        val PLACEHOLDER = Regex("""\{([^{}]*)\}""")
        val ISO_DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        val ISO_DATE_PARTS = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
        val ROLE_WORDS = mapOf(
            "LZ" to "to", "PZ" to "pickup at", "FARP" to "fuel at", "HA" to "holding at", "LAAGER" to "laager at", "FALSE_LZ" to "false insertion at",
        )

        /** The diagram normaliser's clock and ids, never read here (the brief gives it both), fixed so compiling never reads either. */
        val FIXED_ENVIRONMENT = DiagramNormalizer.Environment(now = { "" }, newId = { "" })
    }
}

private fun str(text: String?): JsonElement? = text?.let(::JsonPrimitive)

/** Puts [value], null as JSON null (the web's `?? null`). */
private fun JsonObjectBuilder.putValue(key: String, value: JsonElement?) {
    put(key, value ?: JsonNull)
}

/** A value from the document or the plan as JSON.stringify writes it: numbers as JavaScript writes them, every object's keys in its order. */
private fun asJavaScriptWrites(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(Js.orderedKeys(value.keys).associateWith { asJavaScriptWrites(value.getValue(it)) })
    is JsonArray -> JsonArray(value.map(::asJavaScriptWrites))
    else -> Js.normalized(value)
}
